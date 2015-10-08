/*
 * #%L
 * BigDataViewer core classes with minimal dependencies
 * %%
 * Copyright (C) 2012 - 2015 BigDataViewer authors
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package bdv.img.cache;

import java.util.ArrayList;

import net.imglib2.img.basictypeaccess.volatiles.VolatileAccess;
import bdv.cache.SoftRefVolatileCacheImp;
import bdv.cache.VolatileCache;
import bdv.cache.VolatileCacheEntry;
import bdv.cache.VolatileCacheValue;
import bdv.cache.VolatileCacheValueLoader;
import bdv.img.cache.CacheIoTiming.IoStatistics;
import bdv.img.cache.CacheIoTiming.IoTimeBudget;
import bdv.img.cache.VolatileImgCells.CellCache;

public class VolatileGlobalCellCache implements Cache
{
	private final int maxNumLevels;

	static class Key
	{
		private final int timepoint;

		private final int setup;

		private final int level;

		private final int index;

		private final int[] cellDims;

		private final long[] cellMin;

		public Key( final int timepoint, final int setup, final int level, final int index, final int[] cellDims, final long[] cellMin )
		{
			this.timepoint = timepoint;
			this.setup = setup;
			this.level = level;
			this.index = index;
			this.cellDims = cellDims;
			this.cellMin = cellMin;

			int value = 17;
			value = 31 * value + index;
			value = 31 * value + level;
			value = 31 * value + setup;
			value = 31 * value + timepoint;
			hashcode = value;
		}

		@Override
		public boolean equals( final Object other )
		{
			if ( this == other )
				return true;
			if ( !( other instanceof VolatileGlobalCellCache.Key ) )
				return false;
			final Key that = ( Key ) other;
			return ( this.timepoint == that.timepoint ) && ( this.setup == that.setup ) && ( this.level == that.level ) && ( this.index == that.index );
		}

		final int hashcode;

		@Override
		public int hashCode()
		{
			return hashcode;
		}

		protected int[] getCellDims()
		{
			return cellDims;
		}

		protected long[] getCellMin()
		{
			return cellMin;
		}
	}

	protected VolatileCache volatileCache = SoftRefVolatileCacheImp.getInstance();

	protected final BlockingFetchQueues< Object > queue;

	protected volatile long currentQueueFrame = 0;

	class Fetcher extends Thread
	{
		@Override
		public final void run()
		{
			Object key = null;
			while ( true )
			{
				while ( key == null )
					try
					{
						key = queue.take();
					}
					catch ( final InterruptedException e )
					{}
				long waitMillis = pauseUntilTimeMillis - System.currentTimeMillis();
				while ( waitMillis > 0 )
				{
					try
					{
						synchronized ( lock )
						{
							lock.wait( waitMillis );
						}
					}
					catch ( final InterruptedException e )
					{}
					waitMillis = pauseUntilTimeMillis - System.currentTimeMillis();
				}
				try
				{
					volatileCache.loadIfNotValid( key );
					key = null;
				}
				catch ( final InterruptedException e )
				{}
			}
		}

		private final Object lock = new Object();

		private volatile long pauseUntilTimeMillis = 0;

		public void pauseUntil( final long timeMillis )
		{
			pauseUntilTimeMillis = timeMillis;
			interrupt();
		}

		public void wakeUp()
		{
			pauseUntilTimeMillis = 0;
			synchronized ( lock )
			{
				lock.notify();
			}
		}
	}

	/**
	 * pause all {@link Fetcher} threads for the specified number of milliseconds.
	 */
	public void pauseFetcherThreadsFor( final long ms )
	{
		pauseFetcherThreadsUntil( System.currentTimeMillis() + ms );
	}

	/**
	 * pause all {@link Fetcher} threads until the given time (see
	 * {@link System#currentTimeMillis()}).
	 */
	public void pauseFetcherThreadsUntil( final long timeMillis )
	{
		for ( final Fetcher f : fetchers )
			f.pauseUntil( timeMillis );
	}

	/**
	 * Wake up all Fetcher threads immediately. This ends any
	 * {@link #pauseFetcherThreadsFor(long)} and
	 * {@link #pauseFetcherThreadsUntil(long)} set earlier.
	 */
	public void wakeFetcherThreads()
	{
		for ( final Fetcher f : fetchers )
			f.wakeUp();
	}

	private final ArrayList< Fetcher > fetchers;

	private final CacheIoTiming cacheIoTiming;

	@Deprecated
	public VolatileGlobalCellCache( final int maxNumTimepoints, final int maxNumSetups, final int maxNumLevels, final int numFetcherThreads )
	{
		this( maxNumLevels, numFetcherThreads );
	}

	/**
	 * @param maxNumLevels
	 *            the highest occurring mipmap level plus 1.
	 * @param numFetcherThreads
	 */
	public VolatileGlobalCellCache( final int maxNumLevels, final int numFetcherThreads )
	{
		this.maxNumLevels = maxNumLevels;

		cacheIoTiming = new CacheIoTiming();
		queue = new BlockingFetchQueues< Object >( maxNumLevels );
		fetchers = new ArrayList< Fetcher >();
		for ( int i = 0; i < numFetcherThreads; ++i )
		{
			final Fetcher f = new Fetcher();
			f.setDaemon( true );
			f.setName( "Fetcher-" + i );
			fetchers.add( f );
			f.start();
		}
	}

	/**
	 * Enqueue the {@link Entry} if it hasn't been enqueued for this frame
	 * already.
	 */
	protected void enqueueEntry( final VolatileCacheEntry< ?, ? > entry, final int priority, final boolean enqueuToFront )
	{
		if ( entry.getEnqueueFrame() < currentQueueFrame )
		{
			entry.setEnqueueFrame( currentQueueFrame );
			queue.put( entry.getKey(), priority, enqueuToFront );
		}
	}

	/**
	 * Load the data for the {@link Entry} if it is not yet loaded (valid) and
	 * there is enough {@link IoTimeBudget} left. Otherwise, enqueue the
	 * {@link Entry} if it hasn't been enqueued for this frame already.
	 */
	protected void loadOrEnqueue( final VolatileCacheEntry< ?, ? > entry, final int priority, final boolean enqueuToFront )
	{
		final IoStatistics stats = cacheIoTiming.getThreadGroupIoStatistics();
		final IoTimeBudget budget = stats.getIoTimeBudget();
		final long timeLeft = budget.timeLeft( priority );
		if ( timeLeft > 0 )
		{
			synchronized ( entry )
			{
				if ( entry.getValue().isValid() )
					return;
				enqueueEntry( entry, priority, enqueuToFront );
				final long t0 = stats.getIoNanoTime();
				stats.start();
				try
				{
					entry.wait( timeLeft  / 1000000l, 1 );
				}
				catch ( final InterruptedException e )
				{}
				stats.stop();
				final long t = stats.getIoNanoTime() - t0;
				budget.use( t, priority );
			}
		}
		else
			enqueueEntry( entry, priority, enqueuToFront );
	}

	private void loadEntryWithCacheHints( final VolatileCacheEntry< ?, ? > entry, final CacheHints cacheHints )
	{
		switch ( cacheHints.getLoadingStrategy() )
		{
		case VOLATILE:
		default:
			enqueueEntry( entry, cacheHints.getQueuePriority(), cacheHints.isEnqueuToFront() );
			break;
		case BLOCKING:
			while ( true )
				try
				{
					entry.loadIfNotValid();
					break;
				}
				catch ( final InterruptedException e )
				{}
			break;
		case BUDGETED:
			if ( !entry.getValue().isValid() )
				loadOrEnqueue( entry, cacheHints.getQueuePriority(), cacheHints.isEnqueuToFront() );
			break;
		case DONTLOAD:
			break;
		}
	}

	/**
	 * Get a cell if it is in the cache or null. Note, that a cell being in the
	 * cache only means that there is a data array, but not necessarily that the
	 * data has already been loaded.
	 *
	 * If the cell data has not been loaded, do the following, depending on the
	 * {@link LoadingStrategy}:
	 * <ul>
	 *   <li> {@link LoadingStrategy#VOLATILE}:
	 *        Enqueue the cell for asynchronous loading by a fetcher thread, if
	 *        it has not been enqueued in the current frame already.
	 *   <li> {@link LoadingStrategy#BLOCKING}:
	 *        Load the cell data immediately.
	 *   <li> {@link LoadingStrategy#BUDGETED}:
	 *        Load the cell data immediately if there is enough
	 *        {@link IoTimeBudget} left for the current thread group.
	 *        Otherwise enqueue for asynchronous loading, if it has not been
	 *        enqueued in the current frame already.
	 *   <li> {@link LoadingStrategy#DONTLOAD}:
	 *        Do nothing.
	 * </ul>
	 *
	 * @param timepoint
	 *            timepoint coordinate of the cell
	 * @param setup
	 *            setup coordinate of the cell
	 * @param level
	 *            level coordinate of the cell
	 * @param index
	 *            index of the cell (flattened spatial coordinate of the cell)
	 * @param cacheHints
	 *            {@link LoadingStrategy}, queue priority, and queue order.
	 * @return a cell with the specified coordinates or null.
	 */
	public < V extends VolatileCacheValue > V getGlobalIfCached( final Key key, final CacheHints cacheHints )
	{
		final VolatileCacheEntry< Key, V > entry = volatileCache.get( key );
		if ( entry != null )
		{
			loadEntryWithCacheHints( entry, cacheHints );
			return entry.getValue();
		}
		return null;
	}

	private final Object cacheLock = new Object();

	/**
	 * Create a new cell with the specified coordinates, if it isn't in the
	 * cache already. Depending on the {@link LoadingStrategy}, do the
	 * following:
	 * <ul>
	 *   <li> {@link LoadingStrategy#VOLATILE}:
	 *        Enqueue the cell for asynchronous loading by a fetcher thread.
	 *   <li> {@link LoadingStrategy#BLOCKING}:
	 *        Load the cell data immediately.
	 *   <li> {@link LoadingStrategy#BUDGETED}:
	 *        Load the cell data immediately if there is enough
	 *        {@link IoTimeBudget} left for the current thread group.
	 *        Otherwise enqueue for asynchronous loading.
	 *   <li> {@link LoadingStrategy#DONTLOAD}:
	 *        Do nothing.
	 * </ul>
	 *
	 * @param cellDims
	 *            dimensions of the cell in pixels
	 * @param cellMin
	 *            minimum spatial coordinates of the cell in pixels
	 * @param timepoint
	 *            timepoint coordinate of the cell
	 * @param setup
	 *            setup coordinate of the cell
	 * @param level
	 *            level coordinate of the cell
	 * @param index
	 *            index of the cell (flattened spatial coordinate of the cell)
	 * @param cacheHints
	 *            {@link LoadingStrategy}, queue priority, and queue order.
	 * @return a cell with the specified coordinates.
	 */
	public < K, V extends VolatileCacheValue > V createGlobal( final K key, final CacheHints cacheHints, final VolatileCacheValueLoader< K, V > cacheLoader )
	{
		VolatileCacheEntry< K, V > entry = null;

		synchronized ( cacheLock )
		{
			entry = volatileCache.get( key );
			if ( entry == null )
			{
				final V value = cacheLoader.createEmptyValue( key );
				entry = volatileCache.put( key, value, cacheLoader );
			}
		}

		loadEntryWithCacheHints( entry, cacheHints );
		return entry.getValue();
	}

	/**
	 * Prepare the cache for providing data for the "next frame":
	 * <ul>
	 * <li>the contents of fetch queues is moved to the prefetch.
	 * <li>some cleaning up of garbage collected entries ({@link #finalizeRemovedCacheEntries()}).
	 * <li>the internal frame counter is incremented, which will enable
	 * previously enqueued requests to be enqueued again for the new frame.
	 * </ul>
	 */
	@Override
	public void prepareNextFrame()
	{
		queue.clear();
		volatileCache.finalizeRemovedCacheEntries();
		++currentQueueFrame;
	}

	/**
	 * (Re-)initialize the IO time budget, that is, the time that can be spent
	 * in blocking IO per frame/
	 *
	 * @param partialBudget
	 *            Initial budget (in nanoseconds) for priority levels 0 through
	 *            <em>n</em>. The budget for level <em>i&gt;j</em> must always be
	 *            smaller-equal the budget for level <em>j</em>. If <em>n</em>
	 *            is smaller than the maximum number of mipmap levels, the
	 *            remaining priority levels are filled up with budget[n].
	 */
	@Override
	public void initIoTimeBudget( final long[] partialBudget )
	{
		final IoStatistics stats = cacheIoTiming.getThreadGroupIoStatistics();
		if ( stats.getIoTimeBudget() == null )
			stats.setIoTimeBudget( new IoTimeBudget( maxNumLevels ) );
		stats.getIoTimeBudget().reset( partialBudget );
	}

	/**
	 * Get the {@link CacheIoTiming} that provides per thread-group IO
	 * statistics and budget.
	 */
	@Override
	public CacheIoTiming getCacheIoTiming()
	{
		return cacheIoTiming;
	}

	/**
	 * Remove all references to loaded data as well as all enqueued requests
	 * from the cache.
	 */
	public void clearCache()
	{
		// TODO: we should only clear out our own cache entries not the entire global cache!
		volatileCache.clearCache();
		prepareNextFrame();
		// TODO: add a full clear to BlockingFetchQueues.
		// (BlockingFetchQueues.clear() moves stuff to the prefetchQueue.)
	}

	/**
	 * Wraps a {@link CacheArrayLoader} as a {@link VolatileCacheValueLoader}.
	 */
	public static class CacheArrayLoaderWrapper< A extends VolatileAccess > implements VolatileCacheValueLoader< Key, VolatileCell< A > >
	{
		private final CacheArrayLoader< A > loader;

		public CacheArrayLoaderWrapper( final CacheArrayLoader< A > loader )
		{
			this.loader = loader;
		}

		@Override
		public VolatileCell< A > createEmptyValue( final Key key )
		{
			final VolatileCell< A > cell = new VolatileCell< A >( key.cellDims, key.cellMin, loader.emptyArray( key.getCellDims() ) );
			return cell;
		}

		@Override
		public VolatileCell< A > load( final Key key ) throws InterruptedException
		{
			final VolatileCell< A > cell = new VolatileCell< A >( key.cellDims, key.cellMin, loader.loadArray( key.timepoint, key.setup, key.level, key.cellDims, key.cellMin ) );
			return cell;
		}
	}

	/**
	 * A {@link CellCache} that forwards to the {@link VolatileGlobalCellCache}.
	 *
	 * @param <A>
	 *
	 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
	 */
	public class VolatileCellCache< A extends VolatileAccess > implements CellCache< A >
	{
		private final int timepoint;

		private final int setup;

		private final int level;

		private CacheHints cacheHints;

		private final VolatileCacheValueLoader< Key, VolatileCell< A > > loader;

		public VolatileCellCache( final int timepoint, final int setup, final int level, final CacheHints cacheHints, final CacheArrayLoader< A > cacheArrayLoader )
		{
			this.timepoint = timepoint;
			this.setup = setup;
			this.level = level;
			this.cacheHints = cacheHints;
			this.loader = new CacheArrayLoaderWrapper< A >( cacheArrayLoader );
		}

		@Override
		public VolatileCell< A > get( final int index )
		{
			final Key key = new Key( timepoint, setup, level, index, null, null );
			return getGlobalIfCached( key, cacheHints );
		}

		@Override
		public VolatileCell< A > load( final int index, final int[] cellDims, final long[] cellMin )
		{
			final Key key = new Key( timepoint, setup, level, index, cellDims, cellMin );
			return createGlobal( key, cacheHints, loader );
		}

		@Override
		public void setCacheHints( final CacheHints cacheHints )
		{
			this.cacheHints = cacheHints;
		}
	}
}
