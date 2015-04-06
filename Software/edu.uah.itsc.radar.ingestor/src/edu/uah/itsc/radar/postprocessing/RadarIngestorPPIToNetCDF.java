package edu.uah.itsc.radar.postprocessing;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import ucar.ma2.ArrayFloat;
import ucar.ma2.DataType;
import ucar.ma2.Index;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFileWriter;
import ucar.nc2.Variable;

import edu.uah.itsc.radar.config.RadarConfig;

public class RadarIngestorPPIToNetCDF extends Thread{

	//Constants
	//NOTE CHANGING THESE PARAMETERS CHANGES THE LEVEL OF DETAIL OF PLOT
	private static final int latWidth = 500;			//CHANING THIS REQUIRES RECOMPUTING BOUNDS in GeoServer
	private static final int lonWidth = 500;			//   "		"	   "		"		   "	" 	"
	private static final float roundConst = 1000.0f;
	private static final int angleCount = 15;			
	private static final int rangeCount = 15;
	private static final float coverageArea = 980 * 150000;		// Range to be covered (Radius) = (# of Gates * GateWidth) 

	//IO variables
	private String fileName, outputFileName;
	private File dataFile = null;
	private DataInputStream dis = null;

	//Processing logic variables
	private float radarLat, radarLon;
	private Vector<Float> lats = null;
	private Vector<Float> lons = null;

	//Constructor
	public RadarIngestorPPIToNetCDF(String fileName, float radarLat, float radarLon, String outputFileName){
		this.fileName = fileName;
		this.radarLat = radarLat;
		this.radarLon = radarLon;

		this.outputFileName = outputFileName;
		init();

	}

	//Initialize thread()
	public void init(){

		//Open the date file
		dataFile = new File(fileName);

		//Create a data input stream
		try {
			dis = new DataInputStream(new FileInputStream(dataFile));
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		//Determine the coverage range for this data
		//float angle = 0.5f;
		float angle = (float) Math.toDegrees(coverageArea /  RadarConfig.RADIUS_OF_EARTH);
		float minLat = this.radarLat - angle;
		float maxLat = this.radarLat + angle;
		float minLon = this.radarLon - angle;
		float maxLon = this.radarLon + angle;

		//Detemine the values for lat and lon axis
		float steppingSize, temp;

		//Generate all the latitudes
		lats = new Vector<Float>();
		steppingSize = (maxLat - minLat) / latWidth;
		for(float lat = minLat; lat <= maxLat; lat += steppingSize) {
			temp = Math.round(lat * roundConst) / roundConst;
			if(!lats.contains(temp))
				lats.add(temp);
		}

		//Generate all the longitudes
		lons = new Vector<Float>();
		steppingSize = (maxLon - minLon) / lonWidth;
		for(float lon = minLon; lon <= maxLon; lon += steppingSize){
			temp = Math.round(lon * roundConst) / roundConst;
			if(!lons.contains(temp)) 
				lons.add(temp);
		}
	}

	@Override
	public void run(){
		System.out.println("Converting '" + this.fileName + "' to NetCDF...");
		try {

			//----------------------------------------
			//PART 1 : Create CF Compliant NetCDF file
			//----------------------------------------

			//Create new file
			NetcdfFileWriter writer = NetcdfFileWriter.createNew(NetcdfFileWriter.Version.netcdf3, this.outputFileName, null);

			//Dimension variables for lat and lon
			Dimension latDim = writer.addDimension(null, "lat", lats.size());
			Dimension lonDim = writer.addDimension(null, "lon", lons.size());

			//Latitude variable with CF metadata
			Variable lat = writer.addVariable(null, "lat", DataType.FLOAT, "lat");
			lat.addAttribute(new Attribute("standard_name","latitude"));
			lat.addAttribute(new Attribute("long_name","latitude of the observation"));
			lat.addAttribute(new Attribute("units","degrees_north"));

			//Longitude variable with CF metadata
			Variable lon = writer.addVariable(null, "lon", DataType.FLOAT, "lon");
			lon.addAttribute(new Attribute("standard_name","longitude"));
			lon.addAttribute(new Attribute("long_name","longitude of the observation"));
			lon.addAttribute(new Attribute("units","degrees_east"));

			List<Dimension> dims = new ArrayList<Dimension>();
			dims.add(latDim);
			dims.add(lonDim);

			//Variable that we want to plot... in this case its Reflectivity
			Variable temp = writer.addVariable(null, "reflectivity", DataType.FLOAT, dims);
			temp.addAttribute(new Attribute("standard_name","reflectivity"));
			temp.addAttribute(new Attribute("units","dBZ"));

			//global CF metadata
			writer.addGroupAttribute(null, new Attribute("title","Reflectivity"));
			writer.addGroupAttribute(null, new Attribute("Conventions","CF-1.0"));
			writer.addGroupAttribute(null, new Attribute("source", "Information Technology and Systems Center at UAH"));

			//Create the NetCDF file
			writer.create();

			//------------------------
			// PART 2 : Writing data
			//------------------------

			//Writing data 
			int i,j;
			ArrayFloat d = null;
			Index im = null;

			//latitude
			d = new ArrayFloat.D1(lats.size());
			im = d.getIndex();
			for(i=0; i < lats.size(); ++i)
				d.setFloat(im.set(i),lats.get(i));
			writer.write(lat, d);

			//Longitude
			d = new ArrayFloat.D1(lons.size());
			im = d.getIndex();
			for(i=0; i < lons.size(); ++i)
				d.setFloat(im.set(i),lons.get(i));
			writer.write(lon, d);

			//Write data
			int shape[] = temp.getShape();
			d = new ArrayFloat.D2(shape[0],shape[1]);
			im = d.getIndex();

			for(i=0; i < lats.size(); ++i)
				for(j=0; j < lons.size(); ++j)
					d.setFloat(im.set(i,j), Float.NaN);

			//Reflectivity data
			int count = 0;
			String data = null;
			String s[] = null;
			float startAz, endAz, startRange, endRange, value;
			float _lat, _lon;
			float angleResolution;
			float rangeResolution;

			while((data = dis.readLine()) != null && data.length() > 0){
				
				s = data.split(",");

				if(s.length < 5) continue; //Skip if line is not of correct length

				//Parse the parameters
				startAz = Float.parseFloat(s[0]);
				endAz = Float.parseFloat(s[1]);
				startRange = Float.parseFloat(s[2]);
				endRange = Float.parseFloat(s[3]);
				value = Float.parseFloat(s[4]);
				
				if(value < -32.0f) continue; //Ignore noise

				//Skip in case of equal values
				if(startRange == endRange || endAz == startAz) continue;

				angleResolution = (endAz - startAz) / (float)angleCount;
				rangeResolution = (endRange - startRange) / (float)rangeCount;

				for(float _range = startRange; _range <= endRange; _range += rangeResolution){
					for(float _angle = startAz; _angle <= endAz; _angle += angleResolution){

						//Latitude
						_lat = (float) (_range * Math.sin( Math.toRadians(_angle)));
						_lat = (float) (radarLat + Math.toDegrees(_lat / RadarConfig.RADIUS_OF_EARTH));
						_lat = Math.round(_lat * roundConst) / roundConst;

						//Longitude
						_lon = (float) (_range * Math.cos( Math.toRadians(_angle)));
						_lon = (float) (radarLon + Math.toDegrees(_lon / RadarConfig.RADIUS_OF_EARTH));
						_lon = Math.round(_lon * roundConst) / roundConst;

						//Determine x and y axis index for this value
						i = lats.indexOf(_lat);
						j = lons.indexOf(_lon);

						//If index values are valid push it to array
						if( i >= 0 && j >=0 ){
							d.setFloat(im.set(i,j), value);
						}
					}
				}
			}
			//Write to file
			writer.write(temp, d);

			//save the file
			writer.close();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		catch (InvalidRangeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("Created '" + this.outputFileName + "' file!");
		//		if(this.dataFile.delete()){
		//			System.out.println("Deleted " + this.fileName + "!");
		//		}
	}
}
