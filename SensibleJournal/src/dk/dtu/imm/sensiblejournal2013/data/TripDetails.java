package dk.dtu.imm.sensiblejournal2013.data;

import java.util.LinkedList;
import java.util.List;

public class TripDetails {

	private List<Float> distances;
	private List<Long> durations;
	private List<Float> speeds;
	private List<String> vehicles;
	private float totalDistance;
	
	public TripDetails() {
		distances = new LinkedList<Float>();
		durations = new LinkedList<Long>();
		speeds = new LinkedList<Float>();
		vehicles = new LinkedList<String>();
	}
	
	public void addDistance(float distance) {
		distances.add(distance);
	}
	
	public void addDuration(long duration) {
		durations.add(duration);
	}
	
	public void addVehicle(String vehicle) {
		vehicles.add(vehicle);
	}
	
	public void addSpeed(float speed) {
		speeds.add(speed);
	}
	
	public void setTotalDistance(float totalDistance) {
		this.totalDistance = totalDistance;
	}
	
	public float getDistance(int i) {
		return distances.get(i);
	}
	
	public long getDuration(int i) {
		return durations.get(i);
	}
	
	public String getVehicle(int i) {
		return vehicles.get(i);
	}
	
	public Float getSpeed(int i) {
		return speeds.get(i);
	}
	
	public float getTotalDistance() {
		return totalDistance;
	}
}
