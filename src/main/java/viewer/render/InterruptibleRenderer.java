package viewer.render;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.imglib2.AbstractInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import viewer.hdf5.img.Hdf5GlobalCellCache;
import viewer.util.StopWatch;

public class InterruptibleRenderer< A, B > extends AbstractInterval
{
	final protected RandomAccessible< A > source;

	final protected Converter< A, B > converter;

	protected long lastFrameRenderNanoTime;

	protected long lastFrameIoNanoTime;

	protected long ioTimeOutNanos;

	protected Runnable ioTimeOutRunnable;

	public void setIoTimeOut( final long nanos, final Runnable runnable )
	{
		ioTimeOutNanos = nanos;
		ioTimeOutRunnable = runnable;
	}

	public InterruptibleRenderer( final RandomAccessible< A > source, final Converter< A, B > converter )
	{
		super( new long[ source.numDimensions() ] );
		this.source = source;
		this.converter = converter;
		lastFrameRenderNanoTime = -1;
		lastFrameIoNanoTime = -1;
		ioTimeOutNanos = -1;
	}

	public boolean map( final RandomAccessibleInterval< B > target )
	{
		return mapMultithreaded( target, 8 );
	}

	public boolean mapMultithreaded( final RandomAccessibleInterval< B > target, final int numThreads )
	{
		interrupted.set( false );

		final StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		final long startTimeTotal = stopWatch.nanoTime();
		final long startTimeIo = Hdf5GlobalCellCache.getThreadGroupIoNanoTime();
//		final long startIoBytes = Hdf5GlobalCellCache.getThreadIoBytes();

		if ( ioTimeOutNanos > 0 )
			Hdf5GlobalCellCache.setThreadGroupIoNanoTimeout( startTimeIo + ioTimeOutNanos, new Runnable()
			{
				@Override
				public void run()
				{
					ioTimeOutRunnable.run();
					interrupted.set( true );
				}
			} );
		else
			Hdf5GlobalCellCache.clearThreadGroupIoNanoTimeout();

		min[ 0 ] = target.min( 0 );
		min[ 1 ] = target.min( 1 );
		max[ 0 ] = target.max( 0 );
		max[ 1 ] = target.max( 1 );

		final long cr = -target.dimension( 0 );

		final int width = ( int ) target.dimension( 0 );
		final int height = ( int ) target.dimension( 1 );

		final ExecutorService ex = Executors.newFixedThreadPool( numThreads );
		final int numTasks = numThreads > 1 ? numThreads * 4 : 1;
		for ( int taskNum = 0; taskNum < numTasks; ++taskNum )
		{
			final RandomAccess< A > sourceRandomAccess = source.randomAccess( this );
			final RandomAccess< B > targetRandomAccess = target.randomAccess( target );

			final double h = ( double ) height / numTasks;
			final long myMinY = min[ 1 ] + ( int ) ( taskNum * h );
			final long nextMinY = min[ 1 ] + ( (taskNum == numTasks - 1 ) ? height : ( int ) ( taskNum * h + h ) );
			final long myHeight = nextMinY - myMinY;

			final Runnable r = new Runnable()
			{
				@Override
				public void run()
				{
					sourceRandomAccess.setPosition( min );
					sourceRandomAccess.setPosition( myMinY, 1 );
					targetRandomAccess.setPosition( min[ 0 ], 0 );
					targetRandomAccess.setPosition( myMinY, 1 );
					for ( int y = 0; y < myHeight; ++y )
					{
						if ( interrupted.get() )
							break;
						for ( int x = 0; x < width; ++x )
						{
							converter.convert( sourceRandomAccess.get(), targetRandomAccess.get() );
							sourceRandomAccess.fwd( 0 );
							targetRandomAccess.fwd( 0 );
						}
						sourceRandomAccess.move( cr, 0 );
						targetRandomAccess.move( cr, 0 );
						sourceRandomAccess.fwd( 1 );
						targetRandomAccess.fwd( 1 );
					}
				}
			};
			ex.execute( r );
		}
		ex.shutdown();
		try
		{
			ex.awaitTermination( 1000, TimeUnit.DAYS );
		}
		catch ( final InterruptedException e )
		{
			e.printStackTrace();
		}

//		final long numIoBytes = Hdf5GlobalCellCache.getThreadIoBytes() - startIoBytes;
		final long lastFrameTime = stopWatch.nanoTime() - startTimeTotal;
		lastFrameIoNanoTime = Hdf5GlobalCellCache.getThreadGroupIoNanoTime() - startTimeIo;
		lastFrameRenderNanoTime = lastFrameTime - lastFrameIoNanoTime;

//		if ( wasInterrupted )
//			System.out.println( "rendering was interrupted." );
//		System.out.println( String.format( "rendering:%4d ms   io:%4d ms   (total:%4d ms)", lastFrameRenderNanoTime / 1000000, lastFrameIoNanoTime / 1000000, lastFrameTime / 1000000 ) );
//		System.out.println( lastFrameTime/1000000 + " ms  (io = " + lastFrameIoNanoTime/1000000 + " ms,  render = " + lastFrameRenderNanoTime/1000000 + " ms)" );
//		final double bytesPerSecond = 1000.0 * 1000000.0 * ( ( double ) numIoBytes / lastFrameIoNanoTime ) / 1024.0;
//		if ( ! Double.isNaN( bytesPerSecond ) )
//			System.out.println( String.format( "%.0f kB/s", bytesPerSecond ) );

		return ! interrupted.get();
	}

	protected AtomicBoolean interrupted = new AtomicBoolean();

	public void cancel()
	{
//		System.out.println( "interrupting..." );
		interrupted.set( true );
		Hdf5GlobalCellCache.setThreadGroupIoNanoTimeout( Hdf5GlobalCellCache.getThreadGroupIoNanoTime(), null );
	}

	public long getLastFrameRenderNanoTime()
	{
		return lastFrameRenderNanoTime;
	}

	// TODO: remove?
	public long getLastFrameIoNanoTime()
	{
		return lastFrameIoNanoTime;
	}
}