package net.preibisch.bigstitcher.spark;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import mpicbg.spim.data.sequence.ViewId;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrWriter;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.parallel.SequentialExecutorService;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.preibisch.bigstitcher.spark.AffineFusion.StorageType;
import net.preibisch.bigstitcher.spark.util.BDV;
import net.preibisch.bigstitcher.spark.util.Grid;
import net.preibisch.bigstitcher.spark.util.Import;
import net.preibisch.bigstitcher.spark.util.Spark;
import net.preibisch.bigstitcher.spark.util.ViewUtil;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonRigidTools;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class NonRigidFusionSpark implements Callable<Void>, Serializable
{
	/**
	 * 
	 */
	private static final long serialVersionUID = 385486695284409953L;

	@Option(names = { "-o", "--n5Path" }, required = true, description = "N5 path for saving, e.g. /home/fused.n5")
	private String n5Path = null;

	@Option(names = { "-d", "--n5Dataset" }, required = true, description = "N5 dataset - it is highly recommended to add s0 to be able to compute a multi-resolution pyramid later, e.g. /ch488/s0")
	private String n5Dataset = null;

	@Option(names = { "--bdv" }, required = false, description = "Write a BigDataViewer-compatible dataset specifying TimepointID, ViewSetupId, e.g. -b 0,0 or -b 4,1")
	private String bdvString = null;

	@Option(names = { "-xo", "--xmlout" }, required = false, description = "path to the new BigDataViewer xml project (if --bdv was selected), e.g. /home/project.xml (default: dataset.xml in basepath for H5, dataset.xml one directory level above basepath for N5)")
	private String xmlOutPath = null;

	@Option(names = {"-s", "--storage"}, defaultValue = "N5", showDefaultValue = CommandLine.Help.Visibility.ALWAYS,
			description = "Dataset storage type, currently supported N5, ZARR (and ONLY for local, multithreaded Spark: HDF5)")
	private StorageType storageType = null;

	@Option(names = "--blockSize", description = "blockSize, you can use smaller blocks for HDF5 (default: 128,128,128)")
	private String blockSizeString = "128,128,128";

	@Option(names = { "-x", "--xml" }, required = true, description = "path to the BigStitcher xml, e.g. /home/project.xml")
	private String xmlPath = null;

	@Option(names = { "-b", "--boundingBox" }, description = "fuse a specific bounding box listed in the XML (default: fuse everything)")
	private String boundingBoxName = null;

	@Option(names = { "-ip", "--interestPoints" }, required = true, description = "provide a list of corresponding interest points to be used for the fusion (e.g. -ip 'beads' -ip 'nuclei'")
	private ArrayList<String> interestPoints = null;


	@Option(names = { "--angleId" }, description = "list the angle ids that should be fused into a single image, you can find them in the XML, e.g. --angleId '0,1,2' (default: all angles)")
	private String angleIds = null;

	@Option(names = { "--tileId" }, description = "list the tile ids that should be fused into a single image, you can find them in the XML, e.g. --tileId '0,1,2' (default: all tiles)")
	private String tileIds = null;

	@Option(names = { "--illuminationId" }, description = "list the illumination ids that should be fused into a single image, you can find them in the XML, e.g. --illuminationId '0,1,2' (default: all illuminations)")
	private String illuminationIds = null;

	@Option(names = { "--channelId" }, description = "list the channel ids that should be fused into a single image, you can find them in the XML (usually just ONE!), e.g. --channelId '0,1,2' (default: all channels)")
	private String channelIds = null;

	@Option(names = { "--timepointId" }, description = "list the timepoint ids that should be fused into a single image, you can find them in the XML (usually just ONE!), e.g. --timepointId '0,1,2' (default: all time points)")
	private String timepointIds = null;

	@Option(names = { "-vi" }, description = "specifically list the view ids (time point, view setup) that should be fused into a single image, e.g. -vi '0,0' -vi '0,1' (default: all view ids)")
	private String[] vi = null;

	
	@Option(names = { "--UINT16" }, description = "save as UINT16 [0...65535], if you choose it you must define min and max intensity (default: fuse as 32 bit float)")
	private boolean uint16 = false;

	@Option(names = { "--UINT8" }, description = "save as UINT8 [0...255], if you choose it you must define min and max intensity (default: fuse as 32 bit float)")
	private boolean uint8 = false;

	@Option(names = { "--minIntensity" }, description = "min intensity for scaling values to the desired range (required for UINT8 and UINT16), e.g. 0.0")
	private Double minIntensity = null;

	@Option(names = { "--maxIntensity" }, description = "max intensity for scaling values to the desired range (required for UINT8 and UINT16), e.g. 2048.0")
	private Double maxIntensity = null;

	// only supported for local spark HDF5 writes, needs to share a writer instance
	private static N5HDF5Writer hdf5DriverVolumeWriter = null;

	@Override
	public Void call() throws Exception
	{
		if ( (this.n5Dataset == null && this.bdvString == null) || (this.n5Dataset != null && this.bdvString != null) )
		{
			System.out.println( "You must define either the n5dataset (e.g. -d /ch488/s0) OR the BigDataViewer specification (e.g. --bdv 0,1)");
			System.exit( 0 );
		}

		Import.validateInputParameters(uint8, uint16, minIntensity, maxIntensity, vi, angleIds, channelIds, illuminationIds, tileIds, timepointIds);

		final SpimData2 data = Spark.getSparkJobSpimData2("", xmlPath);

		// select views to process
		final ArrayList< ViewId > viewIds =
				Import.createViewIds(
						data, vi, angleIds, channelIds, illuminationIds, tileIds, timepointIds);

		if ( viewIds.size() == 0 )
		{
			throw new IllegalArgumentException( "No views to fuse." );
		}
		else
		{
			System.out.println( "Following ViewIds will be fused: ");
			for ( final ViewId v : viewIds )
				System.out.print( "[" + v.getTimePointId() + "," + v.getViewSetupId() + "] " );
			System.out.println();
		}

		if ( interestPoints == null || interestPoints.size() == 0 )
		{
			throw new IllegalArgumentException( "no interest points defined, exiting.");
		}

		for ( final String ip : interestPoints )
			System.out.println( "nonrigid using interestpoint label: " + ip );

		final BoundingBox bb = Import.getBoundingBox( data, viewIds, boundingBoxName );

		final int[] blockSize = Import.csvStringToIntArray(blockSizeString);

		System.out.println( "Fusing: " + bb.getTitle() + ": " + Util.printInterval( bb )  + " with blocksize " + Util.printCoordinates( blockSize ) );

		final DataType dataType;

		if ( uint8 )
		{
			System.out.println( "Fusing to UINT8, min intensity = " + minIntensity + ", max intensity = " + maxIntensity );
			dataType = DataType.UINT8;
		}
		else if ( uint16)
		{
			System.out.println( "Fusing to UINT16, min intensity = " + minIntensity + ", max intensity = " + maxIntensity );
			dataType = DataType.UINT16;
		}
		else
		{
			System.out.println( "Fusing to FLOAT32" );
			dataType = DataType.FLOAT32;
		}

		final long[] dimensions = new long[ bb.numDimensions() ];
		bb.dimensions( dimensions );

		final long[] min = new long[ bb.numDimensions() ];
		bb.min( min );

		
		//
		// final variables for Spark
		//
		final String n5Path = this.n5Path;
		final String n5Dataset = this.n5Dataset != null ? this.n5Dataset : Import.createBDVPath( this.bdvString, this.storageType );
		final String xmlPath = this.xmlPath;
		final StorageType storageType = this.storageType;
		final Compression compression = new GzipCompression( 1 );

		final ArrayList< String > labels = new ArrayList<>(interestPoints);
		final boolean uint8 = this.uint8;
		final boolean uint16 = this.uint16;
		final double minIntensity = (uint8 || uint16 ) ? this.minIntensity : 0;
		final double range;
		if ( uint8 )
			range = ( this.maxIntensity - this.minIntensity ) / 255.0;
		else if ( uint16 )
			range = ( this.maxIntensity - this.minIntensity ) / 65535.0;
		else
			range = 0;
		final int[][] serializedViewIds = Spark.serializeViewIds(viewIds);

		try
		{
			// trigger the N5-blosc error, because if it is triggered for the first
			// time inside Spark, everything crashes
			new N5FSWriter(null);
		}
		catch (Exception e ) {}

		final N5Writer driverVolumeWriter;
		if ( StorageType.N5.equals(storageType) )
			driverVolumeWriter = new N5FSWriter(n5Path);
		else if ( StorageType.ZARR.equals(storageType) )
			driverVolumeWriter = new N5ZarrWriter(n5Path);
		else if ( StorageType.HDF5.equals(storageType) )
			driverVolumeWriter = hdf5DriverVolumeWriter = new N5HDF5Writer(n5Path);
		else
			throw new RuntimeException( "storageType " + storageType + " not supported." );

		System.out.println( "Format being written: " + storageType );

		driverVolumeWriter.createDataset(
				n5Dataset,
				dimensions,
				blockSize,
				dataType,
				compression );

		final List<long[][]> grid = Grid.create( dimensions, blockSize );

		/*
		// using bigger blocksizes than being stored for efficiency (needed for very large datasets)

		final List<long[][]> grid = Grid.create(dimensions,
				new int[] {
						blockSize[0] * 4,
						blockSize[1] * 4,
						blockSize[2] * 4
				},
				blockSize);
		*/

		System.out.println( "numBlocks = " + grid.size() );

		driverVolumeWriter.setAttribute( n5Dataset, "min", min);

		// saving metadata if it is bdv-compatible (we do this first since it might fail)
		if ( bdvString != null )
		{
			BDV.writeBDVMetaData(
					driverVolumeWriter,
					storageType,
					dataType,
					dimensions,
					compression,
					blockSize,
					this.bdvString,
					this.n5Path,
					this.xmlOutPath,
					this.angleIds,
					this.illuminationIds, 
					this.channelIds,
					this.tileIds );

			System.out.println( "Done writing BDV metadata.");
			//System.exit( 0 );
		}

		final SparkConf conf = new SparkConf().setAppName("NonRigidFusion");
		// TODO: REMOVE
		//conf.set("spark.driver.bindAddress", "127.0.0.1");

		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final JavaRDD<long[][]> rdd = sc.parallelize( grid );

		final long time = System.currentTimeMillis();

		rdd.foreach(
				gridBlock -> {
					final SpimData2 dataLocal = Spark.getSparkJobSpimData2("", xmlPath);

					// be smarter, test which ViewIds are actually needed for the block we want to fuse
					final Interval fusedBlock =
							Intervals.translate(
									Intervals.translate(
											new FinalInterval( gridBlock[1] ), // blocksize
											gridBlock[0] ), // block offset
									min ); // min of the randomaccessbileinterval

					// recover views to process
					final List< ViewId > viewsToFuse = new ArrayList<>(); // fuse
					final List< ViewId > allViews = new ArrayList<>();

					for ( int i = 0; i < serializedViewIds.length; ++i )
					{
						final ViewId viewId = Spark.deserializeViewIds(serializedViewIds, i);

						// expand by 50 to be conservative for non-rigid overlaps
						final Interval boundingBox = ViewUtil.getTransformedBoundingBox( dataLocal, viewId );
						final Interval bounds = Intervals.expand( boundingBox, 50 );

						if ( ViewUtil.overlaps( fusedBlock, bounds ) )
							viewsToFuse.add( viewId );

						allViews.add( viewId );
					}

					// nothing to save...
					if ( viewsToFuse.size() == 0 )
						return;

					// test with which views the viewsToFuse overlap
					// TODO: use the actual interest point correspondences maybe (i.e. change in mvr)
					final List< ViewId > viewsToUse = new ArrayList<>(); // used to compute the non-rigid transform

					for ( final ViewId viewId : allViews )
					{
						final Interval boundingBoxView = ViewUtil.getTransformedBoundingBox( dataLocal, viewId );
						final Interval boundsView = Intervals.expand( boundingBoxView, 25 );

						for ( final ViewId fusedId : viewsToFuse )
						{
							final Interval boundingBoxFused = ViewUtil.getTransformedBoundingBox( dataLocal, fusedId );
							final Interval boundsFused = Intervals.expand( boundingBoxFused, 25 );
							
							if ( ViewUtil.overlaps( boundsView, boundsFused ))
							{
								viewsToUse.add( viewId );
								break;
							}
						}
					}

					final double downsampling = Double.NaN;
					final double ds = 1.0;
					final int cpd = Math.max( 1, (int)Math.round( 10 / ds ) );

					final int interpolation = 1;
					final long[] controlPointDistance = new long[] { cpd, cpd, cpd };
					final double alpha = 1.0;
					final boolean virtualGrid = false;

					final boolean useBlending = true;
					final boolean useContentBased = false;
					final boolean displayDistances = false;

					final ExecutorService service = new SequentialExecutorService();

					final RandomAccessibleInterval< FloatType > source =
							NonRigidTools.fuseVirtualInterpolatedNonRigid(
									dataLocal,
									viewsToFuse,
									viewsToUse,
									labels,
									useBlending,
									useContentBased,
									displayDistances,
									controlPointDistance,
									alpha,
									virtualGrid,
									interpolation,
									fusedBlock,
									downsampling,
									null,
									service ).getA();

					service.shutdown();

					final N5Writer executorVolumeWriter;

					if ( StorageType.N5.equals(storageType) )
						executorVolumeWriter = new N5FSWriter(n5Path);
					else if ( StorageType.ZARR.equals(storageType) )
						executorVolumeWriter = new N5ZarrWriter(n5Path);
					else if ( StorageType.HDF5.equals(storageType) )
						executorVolumeWriter = hdf5DriverVolumeWriter;
					else
						throw new RuntimeException( "storageType " + storageType + " not supported." );

					if ( uint8 )
					{
						final RandomAccessibleInterval< UnsignedByteType > sourceUINT8 =
								Converters.convert(
										source,(i, o) -> o.setReal( ( i.get() - minIntensity ) / range ),
										new UnsignedByteType());

						//new ImageJ();
						//ImageJFunctions.show( source );
						//ImageJFunctions.show( sourceUINT8 );
						//SimpleMultiThreading.threadHaltUnClean();

						//final RandomAccessibleInterval<UnsignedByteType> sourceGridBlock = Views.offsetInterval(sourceUINT8, gridBlock[0], gridBlock[1]);
						N5Utils.saveBlock(sourceUINT8, executorVolumeWriter, n5Dataset, gridBlock[2]);
					}
					else if ( uint16 )
					{
						final RandomAccessibleInterval< UnsignedShortType > sourceUINT16 =
								Converters.convert(
										source,(i, o) -> o.setReal( ( i.get() - minIntensity ) / range ),
										new UnsignedShortType());

						//final RandomAccessibleInterval<UnsignedShortType> sourceGridBlock = Views.offsetInterval(sourceUINT16, gridBlock[0], gridBlock[1]);
						N5Utils.saveBlock(sourceUINT16, executorVolumeWriter, n5Dataset, gridBlock[2]);
					}
					else
					{
						//final RandomAccessibleInterval<FloatType> sourceGridBlock = Views.offsetInterval(source, gridBlock[0], gridBlock[1]);
						N5Utils.saveBlock(source, executorVolumeWriter, n5Dataset, gridBlock[2]);
					}
				});

		sc.close();

		// close HDF5 writer
		if ( hdf5DriverVolumeWriter != null )
			hdf5DriverVolumeWriter.close();

		System.out.println( "Saved non-rigid, e.g. view with './n5-view -i " + n5Path + " -d " + n5Dataset );
		System.out.println( "done, took: " + (System.currentTimeMillis() - time ) + " ms." );

		return null;
	}

	public static void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new NonRigidFusionSpark()).execute(args));
	}
}
