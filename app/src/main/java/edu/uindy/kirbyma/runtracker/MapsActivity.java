package edu.uindy.kirbyma.runtracker;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewSwitcher;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.JointType;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.RoundCap;
import com.google.android.gms.maps.model.SquareCap;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

//import android.widget.TextView;

/**
 * An activity that tracks the user's position on a map and indicates the route via
 * a polyline on the map. The purpose is to give the user data (distance, time, pace)
 * about a fitness activity (run, walk, bicycle).
 */
public class MapsActivity extends FragmentActivity
        implements OnMapReadyCallback,
                    GoogleMap.OnPolylineClickListener {

    private static final String TAG = MapsActivity.class.getSimpleName();
    private GoogleMap mMap;
    private CameraPosition mCameraPosition;

    // The entry point to the Fused Location Provider.
    private FusedLocationProviderClient mFusedLocationProviderClient;

    // A default location (Sydney, Australia) and default zoom to use when location permission is
    // not granted.
    private final LatLng mDefaultLocation = new LatLng(-33.8523341, 151.2106085);
    private static final int DEFAULT_ZOOM = 15;
    private static final int PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1;
    private boolean mLocationPermissionGranted;

    // The geographical location where the device is currently located. That is, the last-known
    // location retrieved by the Fused Location Provider.
    private Location mLastKnownLocation;
    private Location prevLocation;

    // Keys for storing activity state.
    private static final String KEY_CAMERA_POSITION = "camera_position";
    private static final String KEY_LOCATION = "location";

    // Text Views
    private TextView distanceTextView;
    private TextView timeTextView;
    private TextView avgPaceTextView;

    // View Switcher
    private ViewSwitcher viewSwitcher;

    // Handler (For repeating a runnable(s))
    private Handler handler = new Handler();

    // Variables for drawing polyline indicating path
    private static final int POLYLINE_STROKE_WIDTH_PX = 12; // Polyline thickness
    private ArrayList<LatLng> points = new ArrayList<>();  // List of all GPS points tracked

    // Variables for stopwatch
    private long timeInMilliseconds = 0L;  // Current time elapsed in milliseconds
    private long startTimeMillis = 0L;  // Start time of the activity in milliseconds
    private long endTimeMillis = 0L;  // End time of the activity in milliseconds
    private static final int ONE_SECOND = 1000; // milliseconds
    private static final int FOUR_SECONDS = 4000;  // milliseconds

    // Variables for accumulating the distance traveled
    private float accumDistMeters = 0;  // Accumulated distance in meters
    // Conversion factor from meters to miles
    private static final double METER_TO_MILE_CONVERSION = 0.000621371;

    // Journal Activity Intent
    private Intent journalActivity;

    // Database
    private DatabaseHelper databaseHelper;

    // Runnables
    /**
     * Runnable to update and plot polyline. Handler executes the runnable
     * four(4) seconds.
     */
    private Runnable tracker = new Runnable() {
        @Override
        public void run() {
            getDeviceLocation();
            updateRoute(mLastKnownLocation);

            handler.postDelayed(this, FOUR_SECONDS);
        }
    };


    /**
     * Runnable to update and display time of activity. Handler executes the runnable
     * every second.
     */
    private Runnable stopwatch = new Runnable() {
        @Override
        public void run() {
            timeInMilliseconds = SystemClock.uptimeMillis() - startTimeMillis;
            int secs = (int)(timeInMilliseconds / 1000);
            int mins = secs / 60;
            int hours = mins / 60;
            secs %= 60;
            mins %= 60;

            String time = "Time - " +
                    String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, mins, secs);
            timeTextView.setText(time);

            handler.postDelayed(this, ONE_SECOND);
        }
    };


    /**
     * Runnable to update distance, average pace and display during the activity. Handler
     * executes the runnable four(4) seconds.
     */
    private Runnable dataUpdates = new Runnable() {
        @Override
        public void run() {
            String distance = String.format(Locale.getDefault(), "Distance - %.2f mi",
                    convertMetersToMiles(accumDistMeters));
            distanceTextView.setText(distance);

            float p = getAveragePace(accumDistMeters, timeInMilliseconds);
            String avgPace = String.format(Locale.getDefault(),"Average Pace - %s min/mi",
                    convertDecimalToMins(p));
            avgPaceTextView.setText(avgPace);

            handler.postDelayed(this, FOUR_SECONDS);
        }
    };


    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retrieve location and camera position from saved instance state.
        if (savedInstanceState != null) {
            mLastKnownLocation = savedInstanceState.getParcelable(KEY_LOCATION);
            mCameraPosition = savedInstanceState.getParcelable(KEY_CAMERA_POSITION);
        }

        // Retrieve the content view that renders the map.
        setContentView(R.layout.activity_maps);

        // Construct a FusedLocationProviderClient.
        mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        // Build the map.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        Button runStartBtn = findViewById(R.id.runStartBtn);
        Button runStopBtn = findViewById(R.id.runStopBtn);
        Button journalBtn = findViewById(R.id.journalBtn);
        distanceTextView = findViewById(R.id.distanceTextView);
        timeTextView = findViewById(R.id.timeTextView);
        avgPaceTextView = findViewById(R.id.avgPaceTextView);
        viewSwitcher = findViewById(R.id.viewSwitcher);
        databaseHelper = new DatabaseHelper(this);

        runStartBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view){
                getDeviceStartLocation();
                accumDistMeters = 0;
                viewSwitcher.showNext();
                startTimeMillis = SystemClock.uptimeMillis();
                tracker.run();
                stopwatch.run();
                dataUpdates.run();
            }
        });

        runStopBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view){
                handler.removeCallbacks(tracker);
                handler.removeCallbacks(stopwatch);
                handler.removeCallbacks(dataUpdates);
                endTimeMillis = SystemClock.uptimeMillis();

                addNewRunToDatabase(accumDistMeters, startTimeMillis, endTimeMillis);

                journalActivity = new Intent(getApplicationContext(), JournalActivity.class);
                startActivity(journalActivity);
//                viewSwitcher.showNext();
            }
        });

        journalBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                journalActivity = new Intent(getApplicationContext(), JournalActivity.class);
                startActivity(journalActivity);
            }
        });
    }


    /**
     * Saves the state of the map when the activity is paused.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (mMap != null) {
            outState.putParcelable(KEY_CAMERA_POSITION, mMap.getCameraPosition());
            outState.putParcelable(KEY_LOCATION, mLastKnownLocation);
            super.onSaveInstanceState(outState);
        }
    }


    /**
     * Manipulates the map when it's available.
     * This callback is triggered when the map is ready to be used.
     */
    @Override
    public void onMapReady(GoogleMap map) {
        mMap = map;

        // Prompt the user for permission.
        getLocationPermission();

        // Turn on the My Location layer and the related control on the map.
        updateLocationUI();

        // Get the current location of the device and set the position of the map.
        getDeviceStartLocation();
    }


    /**
     * Gets the last known location of the device and saves location to 'mLastKnownLocation'.
     */
    private void getDeviceLocation() {
        try {
            if (mLocationPermissionGranted) {
                Task<Location> locationResult = mFusedLocationProviderClient.getLastLocation();
                locationResult.addOnCompleteListener(this, new OnCompleteListener<Location>() {
                    @Override
                    public void onComplete(@NonNull Task<Location> task) {
                        if (task.isSuccessful()) {
                            mLastKnownLocation = task.getResult();

                        } else {
                            Log.e(TAG, "Exception: %s", task.getException());
                            mMap.getUiSettings().setMyLocationButtonEnabled(false);
                        }
                    }
                });
            }
        } catch (SecurityException e)  {
            Log.e("Exception: %s", e.getMessage());
        }
    }


    /**
     * Gets the initial location of the device, and positions the map's camera.
     */
    private void getDeviceStartLocation(){
        /*
         * Get the best and most recent location of the device, which may be null in rare
         * cases when a location is not available.
         */
        try {
            if (mLocationPermissionGranted) {
                Task<Location> locationResult = mFusedLocationProviderClient.getLastLocation();
                locationResult.addOnCompleteListener(this, new OnCompleteListener<Location>() {
                    @Override
                    public void onComplete(@NonNull Task<Location> task) {
                        if (task.isSuccessful()) {
                            // Set the map's camera position to the current location of the device.
                            mLastKnownLocation = task.getResult();
                            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                                    new LatLng(mLastKnownLocation.getLatitude(),
                                            mLastKnownLocation.getLongitude()), DEFAULT_ZOOM));
                            prevLocation = mLastKnownLocation;

                        } else {
                            Log.d(TAG, "Current location is null. Using defaults.");
                            Log.e(TAG, "Exception: %s", task.getException());
                            mMap.moveCamera(CameraUpdateFactory
                                    .newLatLngZoom(mDefaultLocation, DEFAULT_ZOOM));
                            mMap.getUiSettings().setMyLocationButtonEnabled(false);
                        }
                    }
                });
            }
        } catch (SecurityException e)  {
            Log.e("Exception: %s", e.getMessage());
        }
    }


    /**
     * Prompts the user for permission to use the device location.
     */
    private void getLocationPermission() {
        /*
         * Request location permission, so that we can get the location of the
         * device. The result of the permission request is handled by a callback,
         * onRequestPermissionsResult.
         */
        if (ContextCompat.checkSelfPermission(this.getApplicationContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mLocationPermissionGranted = true;
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        }
    }


    /**
     * Handles the result of the request for location permissions.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        mLocationPermissionGranted = false;
        switch (requestCode) {
            case PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    mLocationPermissionGranted = true;
                }
            }
        }
        updateLocationUI();
    }


    /**
     * Updates the map's UI settings based on whether the user has granted location permission.
     */
    private void updateLocationUI() {
        if (mMap == null) {
            return;
        }
        try {
            if (mLocationPermissionGranted) {
                mMap.setMyLocationEnabled(true);
                mMap.getUiSettings().setMyLocationButtonEnabled(true);
            } else {
                mMap.setMyLocationEnabled(false);
                mMap.getUiSettings().setMyLocationButtonEnabled(false);
                mLastKnownLocation = null;
                getLocationPermission();
            }
        } catch (SecurityException e)  {
            Log.e("Exception: %s", e.getMessage());
        }
    }


    /**
     * Listens for clicks on a polyline.
     * @param polyline The polyline object that the user has clicked.
     */
    @Override
    public void onPolylineClick(Polyline polyline) {
        // Polyline not clickable
    }


    /**
     * Track the user's path on map using a polyline.
     * @param nextLocation - The user's most recent location
     */
    private void updateRoute(Location nextLocation){
        if (mMap != null){
            points.add(new LatLng(nextLocation.getLatitude(), nextLocation.getLongitude()));

            PolylineOptions polylineOptions = new PolylineOptions()
                    .color(Color.GREEN)
                    .width(POLYLINE_STROKE_WIDTH_PX)
                    .jointType(JointType.BEVEL)
                    .startCap(new RoundCap())
                    .endCap(new SquareCap())
                    .clickable(false);

            polylineOptions.addAll(points);
            mMap.addPolyline(polylineOptions);

            accumDistMeters += prevLocation.distanceTo(nextLocation);
            prevLocation = nextLocation;
        }
    }


    /**
     * Calculate the total time recorded on the stopwatch when activity is
     * finished and return as String.
     * @param startMillis - start time of activity in milliseconds
     * @param endMillis - end time of activity in milliseconds
     * @return - String of stopwatch time, in colon separated format
     */
    private String getStopwatchTime(long startMillis, long endMillis){
        int secs = (int)((endMillis - startMillis) / 1000);
        int mins = secs / 60;
        int hours = mins / 60;
        secs %= 60;
        mins %= 60;
        return String.format(Locale.getDefault(),"%02d:%02d:%02d", hours, mins, secs);
    }


    /**
     * Convert a distance of meters to a distance of miles.
     * @param meters - Distance in meters to be converted to miles
     * @return - Float containing a distance in miles
     */
    private float convertMetersToMiles(float meters){
        return meters * (float)METER_TO_MILE_CONVERSION;
    }


    /**
     * Calculate the average pace throughout the activity
     * @param meters - Accumulated distance tracked
     * @param milliseconds - Total time since start of activity
     * @return - Float representing pace in mins/mi
     */
    private float getAveragePace(float meters, long milliseconds){
        int secs = (int)(milliseconds / 1000);
        float mins = (float)secs / 60;
        float miles = convertMetersToMiles(meters);
        return mins / miles;
    }


    /**
     * Get a string representation of today's date in the device's default
     * timezone and locale.
     * @return - String of date in 'MM/DD/YYYY' format
     */
    private String getDate(){
        GregorianCalendar calendar = new GregorianCalendar();
        int month = calendar.get(Calendar.MONTH) + 1;
        /* Months are indexed starting at 0
        *  Jan = 0, Feb = 1, etc. */
        int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
        int year = calendar.get(Calendar.YEAR);
        return String.format(Locale.getDefault(), "%d/%d/%d", month, dayOfMonth, year);
    }


    /**
     * Add the completed activity to the database to be viewed later.
     * @param accumDistMeters - Total route distance in meters
     * @param startTimeMillis - Start time of the activity in milliseconds
     * @param endTimeMillis - End time of the activity in milliseconds
     */
    private void addNewRunToDatabase(float accumDistMeters, long startTimeMillis, long endTimeMillis) {
        float miles = convertMetersToMiles(accumDistMeters);
        String total_time = getStopwatchTime(startTimeMillis, endTimeMillis);
        float pace = getAveragePace(accumDistMeters, endTimeMillis - startTimeMillis);
        String date = getDate();

        // db.addData() returns boolean regarding success of adding to database
        if (databaseHelper.addData(miles, total_time, pace, date)){
            Toast.makeText(this, "Activity saved", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Something went wrong" , Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * Convert a decimal representing time into 'mm:ss' format.
     * Example: 9.87 minutes => 9 mins 52 secs (9:52)
     * @param decimal - Float of decimal number representing minutes (e.g. 9.87 mins)
     * @return - String of converted decimal in 'mm:ss' format
     */
    private String convertDecimalToMins(float decimal){
        int mins = (int) Math.floor(decimal);
        double fractional = decimal - mins;
        int secs = (int) Math.round(fractional * 60);
        return String.format(Locale.getDefault(), "%d:%02d", mins, secs);
    }
}