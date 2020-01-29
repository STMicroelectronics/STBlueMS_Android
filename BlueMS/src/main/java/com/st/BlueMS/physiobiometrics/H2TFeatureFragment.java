/*
 * Copyright (c) 2019  PhysioBiometrics – All rights reserved
 *
 *  This fragment implements the HeeltoToe algorithm
 * Gyroscope data is streamed over Bluetooth from the Heel2toe device
 *
 * @author  Ted Hill
 * @version 1.0
 * @since   2019-12-25
 */

package com.st.BlueMS.physiobiometrics;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.st.BlueMS.R;
import com.st.BlueMS.demos.PlotFeatureFragment;
import com.st.BlueMS.demos.util.BaseDemoFragment;
import com.st.BlueSTSDK.Feature;
import com.st.BlueSTSDK.Features.FeatureAcceleration;
import com.st.BlueSTSDK.Features.FeatureAutoConfigurable;
import com.st.BlueSTSDK.Features.FeatureGyroscope;
import com.st.BlueSTSDK.Features.FeatureMagnetometer;
import com.st.BlueSTSDK.Node;
import com.st.BlueSTSDK.gui.demos.DemoDescriptionAnnotation;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Fragment implements the HeeltoToe algorithm
 */
@DemoDescriptionAnnotation(name = "Heel2toe detector", iconRes = R.drawable.demo_charts,
        requareOneOf = {FeatureAcceleration.class,
                FeatureGyroscope.class,
                FeatureMagnetometer.class,
        })
public class H2TFeatureFragment extends BaseDemoFragment implements View.OnClickListener,
        FeatureAutoConfigurable.FeatureAutoConfigurationListener{

    private FeatureAutoConfigurable mConfig;

    private double accel_fullscale = 0.000244;  // 8gs = 0.244 mg/LSB
    private double gyro_fullscale = 0.35;   // 1000 dps = 35 mdps/LSB (but already divided by 1o in gyro feature)

    private Context thiscontext;
    private ContentResolver contentResolver;
    private boolean mIsLiveSession;
    private ImageButton mStartPlotButton;

    private Button m25hz, m50hz, mCalibrate;
    private boolean filter;

    /**
     * domain axis label
     */
    //private TextView mAccelData;
    private TextView mGyroData;
    private TextView mH2tstatus;

    // timers fo rH2t session
    //private Timer timer;
    private Reminder reminder;
    private CountDownTimer countdown;
    protected int maxSessionSeconds = 10;
    private int counter;

    // UI management
    private RadioButton mBeepChecked;
    private boolean isBeepChecked;
    private RadioButton mCaptureToFileChecked;
    private boolean isCaptureToFileChecked;
    private boolean isSimulateChecked;
    protected SeekBar mThreshold;
    private TextView mThresholdVal;
    private int goodStepThreshold;

    private SeekBar mMaxTimeBar;
    protected TextView mMaxtime;
    private TextView mCcounttime;

    private int deviceSampleCounter;

    /***************
     * inertial measurement XYZ orientation is dependent on the Hardware chip orientation
     * when laid flat:
     * Z is up/down
     * Y is forward to backward
     * X is left to right
     * the heel2toe is oriented on the side, so X,Y,Z will interchange
     * we will do this manually to start, and detect it later....
     */
    private Spinner spinnerX;
    private Spinner spinnerY;
    private Spinner spinnerZ;
    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 2;
    private int Xcoord = X;
    private int Ycoord = Y;
    private int Zcoord = Z;

    private OutputStream outputStream;
    private boolean captureReady;
    private String dataFilename;

    // capture
    private List<InertialMeasurement> gyroSample = new ArrayList<InertialMeasurement>();
    private List<InertialMeasurement> accelSample = new ArrayList<InertialMeasurement>();
    private long gyroSampleCounter;
    private long accelSampleCounter;

    private TextView mFrequency;
    private long samplingFrequency;
    private int samplingRate;
    private int SAMPLES_TO_DETECT_FREQUENCY = 10;
    private long samplingFirstTimestamp;
    private int DEFAULT_THRESHOLD = -109;


    private FeatureAutoConfigurable mFeature;

    // step detection
    private StepDetect stepDetect;
    private double[] zGyroArrayFilt;
    List<StepResults> allStepResults = new ArrayList<StepResults>();
    List<StepResults> goodstepResults = new ArrayList<StepResults>();
    List<StepResults> badstepResults = new ArrayList<StepResults>();

    // TED for H2t sampling
    List<Feature> h2tFeatures = null;
    private List<FeatureGyroscope> mH2TgyroFeature;
    private Feature.FeatureListener mH2TgyroFeatureListener;

    private List<FeatureAcceleration> mH2TaccelFeature;
    private Feature.FeatureListener mH2TaccelFeatureListener;

    private static final int WRITE_REQUEST_CODE = 101;
    private static final int SDCARD_PERMISSION = 1,
            FOLDER_PICKER_CODE = 2,
            FILE_PICKER_CODE = 3;

    private TextView folder;
    private boolean folderIsSet;

    ToneGenerator toneGen1;

    /**
     * value used for start the configuration procedure
     */
    protected final byte FEATURE_SET_HZ_SLOW = 0x2;
    protected final byte FEATURE_SET_HZ_FAST = 0x5;
    protected final byte FEATURE_CALIBRATE = 0x1;


    Handler handler = new Handler(Looper.getMainLooper()) {
        /*
         * handleMessage() defines the operations to perform when
         * the Handler receives a new Message to process.
         */
        @Override
        public void handleMessage(Message inputMessage) {
            // Gets the image task from the incoming Message object.
            mH2tstatus.setText("Looper message ");
        }
    };


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_h2t_feature, container, false);
        /**
         * This method is call on initialization and follows the STM32 BlueSTSDK pattern
         * @param inflater pointer to layout (GUI)
         * @param container container for this GUI
         * @param savedInstanceState unused                  
         * @return initialized View.
         */

        mStartPlotButton = root.findViewById(R.id.startPlotButton);
        mStartPlotButton.setOnClickListener(new ProcessListener());
        mStartPlotButton.setEnabled(false);

        //mAccelData = root.findViewById(R.id.accelData);
        //mAccelData.setText("Aceleration data");
        mGyroData = root.findViewById(R.id.gyroData);
        mGyroData.setText(" ");
        Resources res = getResources();
        mH2tstatus = root.findViewById(R.id.h2tstatus);
        mH2tstatus.setText("Ready for Walk-Well analysis. Press start button then walk. Process file to simulate. ");

        mBeepChecked = (RadioButton) root.findViewById(R.id.beepGoodStep);
        isBeepChecked = false;
        mBeepChecked.setOnClickListener(new BeepCheckedListener());

        mCaptureToFileChecked = (RadioButton) root.findViewById(R.id.captureToFile);
        isCaptureToFileChecked = false;
        mCaptureToFileChecked.setOnClickListener(new CaptureCheckedListener());

        m25hz = (Button) root.findViewById(R.id.H2T25hz);
        m25hz.setOnClickListener(new H2T25_ButtonListener());
        m50hz = (Button) root.findViewById(R.id.H2T50Hz);
        m50hz.setOnClickListener(new H2T50_ButtonListener());
        mCalibrate = (Button) root.findViewById(R.id.H2Tcalibrate);
        mCalibrate.setOnClickListener(new H2Tcalibrate_ButtonListener());

        /*
        spinnerX = (Spinner) root.findViewById(R.id.spinnerX);
        spinnerX.setSelection(X);
        spinnerX.setOnItemSelectedListener(new SpinnerXListener());
        spinnerY = (Spinner) root.findViewById(R.id.spinnerY);
        spinnerY.setSelection(Y);
        spinnerY.setOnItemSelectedListener(new SpinnerYListener());
        spinnerZ = (Spinner) root.findViewById(R.id.spinnerZ);
        spinnerZ.setSelection(Z);
        spinnerZ.setOnItemSelectedListener(new SpinnerZListener());
        */

        mFrequency = root.findViewById(R.id.frequencyVal);
        mFrequency.setText("25 Hz");
        samplingFrequency = 25; // default

        goodStepThreshold = DEFAULT_THRESHOLD; // default value from matlab
        mThresholdVal = root.findViewById(R.id.thresholdVal);
        mThresholdVal.setText("Threshold: " + goodStepThreshold + " d/s");
        //mThresholdVal.addTextChangedListener(new ThresholdWatcher());

        mThreshold = (SeekBar) root.findViewById(R.id.thresholdBar);
        mThreshold.setProgress(-goodStepThreshold);
        mThreshold.setOnSeekBarChangeListener(new ThresholdListener());

        mCcounttime = root.findViewById(R.id.counttime);
        mCcounttime.setText("0");
        mMaxtime = root.findViewById(R.id.maxtime);
        mMaxtime.setText("Max: " + String.valueOf(maxSessionSeconds) + " s");
        //mMaxtime.addTextChangedListener(new MaxTimeWatcher());

        mMaxTimeBar = (SeekBar) root.findViewById(R.id.MaxTimeBar);
        mMaxTimeBar.setProgress(maxSessionSeconds);
        mMaxTimeBar.setOnSeekBarChangeListener(new MaxTimeListener());

        folder = root.findViewById(R.id.folderLocation);
        folder.setText("set folder location for capture");
        folderIsSet = false;

        captureReady = false;
        outputStream = null;

        this.thiscontext = container.getContext();
        this.contentResolver = thiscontext.getContentResolver();
        toneGen1 = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
        toneGen1.startTone(ToneGenerator.TONE_PROP_BEEP, 500);

        set25HZ();
        mCalibrate.setBackgroundColor(Color.YELLOW);

        return root;
    }

    public void sendH2TCommand(byte command) {
        byte[] data = {8};
        Node node = getNode();
        mH2TgyroFeature = node.getFeatures(FeatureGyroscope.class);
        Feature f = mH2TgyroFeature.get(0);
        f.sendCommand( command , data);
    }

//    public class RemindTask extends TimerTask {
//        /**
//         * This class is used to start and stop a timer for the maximum length of a
//         * walking measurement session
//         * ToDo play a new sound when timed out
//         */
//        public void run() {
//            mH2tstatus.setText("Timeout. Processing data");
//            timer.cancel(); //Terminate the timer thread
//            mIsLiveSession = false;
//            stopH2tFeature();
//            setButtonStartStatus();
//            getResults();
//            folder.setText("");
//            //h2tSummary();
//            mCaptureToFileChecked.setChecked(false);
//
//        }
//    }


    public class Reminder {
        Timer timer;
        RemindTask remindTask;

        public Reminder(int seconds) {
            timer = new Timer();
            remindTask = new RemindTask();
            timer.schedule(remindTask, seconds * 1000);
        }
        public void cancel() {
            remindTask.cancel();
            timer.cancel();
            mH2tstatus.setText("Cancelled. Processing data");
        }

        class RemindTask extends TimerTask {
            public void run() {
                System.out.println("Time's up!");
                mH2tstatus.setText("Timeout. Processing data");
                timer.cancel(); //Terminate the timer thread
                mIsLiveSession = false;
                stopH2tFeature();
                setButtonStartStatus();
                getResults();
                folder.setText("");
                //h2tSummary();
                mCaptureToFileChecked.setChecked(false);
            }
        }
    }

    private class H2TgyroListener implements Feature.FeatureListener {
        /**
         * This class follows the STM32 BlueSTSDK pattern FeatureListener
         * Gyro sample events are processed
         */
        long androidTimestamp;

        public H2TgyroListener() {
            // setup the step detector
        }

        @Override
        public void onUpdate(final Feature f, Feature.Sample sample) {
            /**
             * This class follows the STM32 BlueSTSDK pattern FeatureListener
             * Gyro sample events are processed
             * good and bad steps are detected
             * all data is recorded for subsequent capture to file (if enabled by  the user)
             * @param f Feature - in this case the Gyro feature
             * @param sample contains the sample and the timestamp
             * @exception Exception TODO H2TgyroListener onUpdate error. 
             * @see IOException
             */
            double Xval = 0;
            double Yval = 0;
            double Zval = 0;
            if (mIsLiveSession) {
                try {
                    Xval = sample.data[Xcoord].doubleValue()* gyro_fullscale;
                    Yval = sample.data[Ycoord].doubleValue()* gyro_fullscale;
                    Zval = sample.data[Zcoord].doubleValue()* gyro_fullscale;
                } catch (Exception e) {
                    mIsLiveSession = false;
                    mH2tstatus.setText("Error reading sample data. Session stopped. error = "+ e.toString());
                    e.printStackTrace();
                }
                gyroSampleCounter++;
                gyroSample.add(new InertialMeasurement(gyroSampleCounter, sample.timestamp, Xval, Yval, Zval));
                // first 10 samples used to calculate sampling frequency
                if (gyroSampleCounter <= SAMPLES_TO_DETECT_FREQUENCY+1) {
                    if (gyroSampleCounter == 1) {
                        androidTimestamp = System.currentTimeMillis();
                        samplingFirstTimestamp = sample.timestamp;
                    } else if (gyroSampleCounter == SAMPLES_TO_DETECT_FREQUENCY+1) {
                        // calculate the sampling frequency
                        long msBetweenSamples = (sample.timestamp - samplingFirstTimestamp) / SAMPLES_TO_DETECT_FREQUENCY;
                        long androidTotalTime = System.currentTimeMillis() - androidTimestamp;
                        long avgAndroidTime = Math.round((androidTotalTime/SAMPLES_TO_DETECT_FREQUENCY)/10.0) * 10;
                        samplingFrequency = 1000 / avgAndroidTime;
                    }
                } else {
                    try {
                        zGyroArrayFilt = stepDetect.filter(sample.timestamp, Xval, Yval, Zval, filter);

                        StepResults stepResults = stepDetect.detectStep(zGyroArrayFilt, goodStepThreshold,samplingRate);
                        allStepResults.add(stepResults);
                        if (stepResults.goodstep) {
                            if (isBeepChecked) {
                                toneGen1.startTone(ToneGenerator.TONE_PROP_BEEP, 500);
                            }
                            goodstepResults.add(stepResults);
                        } else if (stepResults.badstep) {
                            badstepResults.add(stepResults);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (gyroSampleCounter % 10 == 0)  {
                    updateGui(() -> {
                        try {
                            mGyroData.setText(sample.data[2].toString());
                        } catch (NullPointerException e) {
                            //this exception can happen when the task is run after the fragment is
                            // destroyed
                            e.printStackTrace();
                        }
                    });
                }
            }

        }//onUpdate
    }

    private class H2TaccelListener implements Feature.FeatureListener {

        @Override
        public void onUpdate(final Feature f, Feature.Sample sample) {
            /**
             * This class follows the STM32 BlueSTSDK pattern FeatureListener
             * Accel sample events are processed
             * all data is recorded for subsequent capture to file (if enabled by  the user)
             * TODO add to captured data. 
             * @param f Feature - in this case the Gyro feature
             * @param sample contains the sample and the timestamp
             * @exception Exception TODO H2TaccelListener onUpdate error. 
             * @see IOException
             */
            double Xval = 0;
            double Yval = 0;
            double Zval = 0;
            if (mIsLiveSession) {
                try {
                    Xval = sample.data[Xcoord].doubleValue()* accel_fullscale;
                    Yval = sample.data[Ycoord].doubleValue()* accel_fullscale;
                    Zval = sample.data[Zcoord].doubleValue()* accel_fullscale;
                    accelSample.add(new InertialMeasurement(++accelSampleCounter, sample.timestamp, Xval, Yval, Zval));
                    //final String dataString = f.toString();
                } catch (Exception e) {
                    mIsLiveSession = false;
                    mH2tstatus.setText("Error reading sample data. Session stopped. error = "+ e.toString());
                    e.printStackTrace();
                }
                /*
                updateGui(() -> {
                    try {
                        mAccelData.setText(dataString);
                    } catch (NullPointerException e) {
                        //this exception can happen when the task is run after the fragment is
                        // destroyed
                    }
                });
                */
            }
        }//onUpdate
    }

    private class ThresholdWatcher implements TextWatcher {
        int value;
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            try {
                value = Integer.parseInt(s.toString());
                if (value < 0) {
                    value = -value;
                    mH2tstatus.setText("warning. threshold is always negative");
                }
                mThresholdVal.setText(String.valueOf(value));
                mThreshold.setProgress(value);
            } catch (NumberFormatException e) {
                mH2tstatus.setText("Error. Threshold must be a number");
                mThreshold.setProgress(DEFAULT_THRESHOLD);
            }
        }
        @Override
        public void afterTextChanged(Editable s) {
        }
    };

    private class MaxTimeWatcher implements TextWatcher {
        int value;
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            try {
                value = Integer.parseInt(s.toString());
                if (value < 0) {
                    value = -value;
                    mH2tstatus.setText("warning. max time is always positive");
                }
                mMaxtime.setText(String.valueOf(value));
                mMaxTimeBar.setProgress(value);
            } catch (NumberFormatException e) {
                mH2tstatus.setText("Error. max time must be a number");
                mMaxtime.setText(String.valueOf(maxSessionSeconds));
                mMaxTimeBar.setProgress(maxSessionSeconds);
            }
        }
        @Override
        public void afterTextChanged(Editable s) {
        }
    };

    private void set25HZ() {
        sendH2TCommand(FEATURE_SET_HZ_SLOW);
        mH2tstatus.setText("Data collection now 25 samples per second");
        mFrequency.setText("25 Hz");
        samplingFrequency = 25; // default
        samplingRate = 40;
        m25hz.setBackgroundColor(Color.GRAY);
        m50hz.setBackgroundColor(Color.LTGRAY);
        filter = false;
    }

    private class H2T25_ButtonListener implements Button.OnClickListener {
            public void onClick(View v) {
                set25HZ();
            }
    }

    private class H2T50_ButtonListener implements Button.OnClickListener {
        public void onClick(View v) {
            sendH2TCommand(FEATURE_SET_HZ_FAST);
            mH2tstatus.setText("Data collection now 50 samples per second");
            mFrequency.setText("50 Hz");
            samplingFrequency = 50; // default
            samplingRate = 20;
            m50hz.setBackgroundColor(Color.GRAY);
            m25hz.setBackgroundColor(Color.LTGRAY);
            filter = true;
        }
    }

    private class H2Tcalibrate_ButtonListener implements Button.OnClickListener {
        public void onClick(View v) {
            sendH2TCommand(FEATURE_CALIBRATE);
            mH2tstatus.setText("calibrating... Finished when you hear 3 beeps " +
                    "from the Heel2toe device");
        }
    }

    private class SpinnerXListener implements Spinner.OnItemSelectedListener {
        /**
         * This class listens for X coordinate spinner changes
         * The orientation of the H2T device is not XYZ standard.
         * as such, the XYZ is changed.
         * TODO automatically detect orientation (Z up/down will have most energy. X left, rigth will have least).
         */
        public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
            Xcoord = position;
        }

        public void onNothingSelected(AdapterView<?> parentView) {
        }
    }

    private class SpinnerYListener implements Spinner.OnItemSelectedListener {
        /**
         * This class listens for Y coordinate spinner changes
         */
        public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
            Ycoord = position;
        }

        public void onNothingSelected(AdapterView<?> parentView) {
        }
    }

    private class SpinnerZListener implements Spinner.OnItemSelectedListener {
        /**
         * This class listens for Z coordinate spinner changes
         */
        public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
            Zcoord = position;
        }

        public void onNothingSelected(AdapterView<?> parentView) {
        }
    }

    private class ThresholdListener implements SeekBar.OnSeekBarChangeListener {
        /**
         * This class listens for good step threshold (gyro Z degrees / second)  seekbar changes
         * the threshold is sent to the StepDetect class
         */
        public void onProgressChanged(SeekBar seekBar, int progress,
                                      boolean fromUser) {
            goodStepThreshold = -progress;
             mThresholdVal.setText(String.valueOf(goodStepThreshold));
        }

        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    private class MaxTimeListener implements SeekBar.OnSeekBarChangeListener {
        /**
         * This class listens for maximum session time seekbar changes
         * mMaxtime is used by RemindTask to stop the session.
         */
        public void onProgressChanged(SeekBar seekBar, int progress,
                                      boolean fromUser) {
            maxSessionSeconds = progress;
            mMaxtime.setText("Max: " + String.valueOf(maxSessionSeconds) + " s");
        }

        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    private class BeepCheckedListener implements View.OnClickListener {
        /**
         * This class turns beeps on or off for good steps detected
         */
        @Override
        public void onClick(View v) {
            if (isBeepChecked) {
                mBeepChecked.setChecked(false);
                isBeepChecked = false;
            } else {
                mBeepChecked.setChecked(true);
                isBeepChecked = true;
                toneGen1.startTone(ToneGenerator.TONE_PROP_BEEP, 500);
            }
        }
    }

    private class CaptureCheckedListener implements View.OnClickListener {
        /**
         * This class sets implements ACTION_CREATE_DOCUMENT Intent
         * to chose where samples should be captured
         * The filename is  set to the current datetime
         * ToDo add some unique ID to identify the android device and/or H2T device
         */
        @Override
        public void onClick(View v) {
            if (isCaptureToFileChecked) {
                closeCaptureStream();
            } else {
                mCaptureToFileChecked.setChecked(true);
                isCaptureToFileChecked = true;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                // filter to only show openable items.
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // Create a file with the requested Mime type
                intent.setType("text/csv");
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
                Date today = Calendar.getInstance().getTime();
                dataFilename = dateFormat.format(today) + ".csv";
                intent.putExtra(Intent.EXTRA_TITLE, dataFilename);
                startActivityForResult(intent, WRITE_REQUEST_CODE);
            }
        }
    }

    private class ProcessListener implements View.OnClickListener {
        /**
         * This class starts a H2T session
         */
        @Override
        public void onClick(View v) {
            if (mIsLiveSession) {
                countdown.cancel();
                mCcounttime.setText("Stopped");
                onH2tFinished("Stopped. Processing steps");
            } else {
                startH2tFeature(); // TED
                setButtonStopStatus();
            }//if-else
        }
    }

    private void closeCaptureStream() {
        /**
         * This method closes a previously opened file capture session.
         */
        if (captureReady) {
            try {
                if (outputStream != null) {
                    outputStream.close();
                    outputStream = null;
                }

            } catch (IOException e) {
                mH2tstatus.setText("Error closing output stream");
            }
        }
        captureReady = false;
        //mCaptureToFileChecked.setChecked(false);
        isCaptureToFileChecked = false;
    }

    /*
     * free the element gui for permit to the gc to free it before recreate the fragment
     */
    @Override
    public void onDestroyView() {
        mStartPlotButton = null;
        super.onDestroyView();
    }

    private void onH2tFinished(String status) {
        System.out.println("Time's up! "+ status);
        counter = 0;
        mH2tstatus.setText("status");
        mIsLiveSession = false;
        stopH2tFeature();
        setButtonStartStatus();
        getResults();
        folder.setText("");
        mCaptureToFileChecked.setChecked(false);
    }

    public void startH2tFeature() {
        /**
         * start Heel2toe processing  for feature data and enable the feature
         *
         */
        Node node = getNode();
        if (node == null)
            return;
        allStepResults = new ArrayList<StepResults>();
        goodstepResults = new ArrayList<StepResults>();
        badstepResults = new ArrayList<StepResults>();
        deviceSampleCounter = 0;

        gyroSample = new ArrayList<InertialMeasurement>();
        accelSample = new ArrayList<InertialMeasurement>();
        gyroSampleCounter = 0;
        accelSampleCounter = 0;

        stepDetect = new StepDetect();
        mH2TgyroFeature = node.getFeatures(FeatureGyroscope.class);
        if (!mH2TgyroFeature.isEmpty()) {
            mH2TgyroFeatureListener = new H2TgyroListener();
            for (Feature f : mH2TgyroFeature) {
                f.addFeatureListener(mH2TgyroFeatureListener);
                node.enableNotification(f);
            }//for
        }

        mH2TaccelFeature = node.getFeatures(FeatureAcceleration.class);
        if (!mH2TaccelFeature.isEmpty()) {
            mH2TaccelFeatureListener = new H2TaccelListener();
            for (Feature f : mH2TaccelFeature) {
                f.addFeatureListener(mH2TaccelFeatureListener);
                node.enableNotification(f);
            }//for
        }
        mIsLiveSession = true;
        toneGen1 = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        toneGen1.startTone(ToneGenerator.TONE_DTMF_6, 500);
        mH2tstatus.setText("Step Detection in Progress");

        //timer = new Timer();
        int maxSessionMilliSeconds = maxSessionSeconds * 1000;
        //reminder = new Reminder(maxSessionMilliSeconds);
        //timer.schedule(new RemindTask(), maxSessionMilliSeconds);

        countdown = new CountDownTimer(maxSessionMilliSeconds, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                mCcounttime.setText(String.valueOf(maxSessionSeconds - counter));
                counter++;
            }

            @Override
            public void onFinish() {
                counter = 0;
                mCcounttime.setText("Finished");
                onH2tFinished("Timeout. Processing steps");
            }
        }.start();
    }

    public void stopH2tFeature() {
        /**
         * stop Heel2toe processing  for feature data and enable the feature
         */
        //mFrequency.setText(samplingFrequency + " Hz");
        Node node = getNode();
        if (node == null)
            return;

        if (mH2TgyroFeature != null && !mH2TgyroFeature.isEmpty()) {
            for (Feature f : mH2TgyroFeature) {
                f.removeFeatureListener(mH2TgyroFeatureListener);
                node.disableNotification(f);
            }//for
        }

        if (mH2TaccelFeature != null && !mH2TaccelFeature.isEmpty()) {
            for (Feature f : mH2TaccelFeature) {
                f.removeFeatureListener(mH2TaccelFeatureListener);
                node.disableNotification(f);
            }//for
        }
    }

    public void getResults() {
        String[] xyz = {"X", "Y", "Z"};
        String results = stepDetect.stepResults(allStepResults, goodstepResults, badstepResults,
                deviceSampleCounter, (int) samplingFrequency);

        results = dataFilename
                + System.getProperty("line.separator") +
                "Sampling frequency: " + samplingFrequency + " Hertz" +
                System.getProperty("line.separator") +
                "Gyroscope XYZ X: " + xyz[Xcoord] + " Y: " + xyz[Ycoord] + " Z: " + xyz[Zcoord] +
                System.getProperty("line.separator") +
                "Threshold: " + goodStepThreshold + " d/s" +
                System.getProperty("line.separator") +
                results;
        mH2tstatus.setText(results);
        if (captureReady) {
            FileProcess fileProcess = new FileProcess();
            FileStatus fs = fileProcess.writeResults(results, outputStream, gyroSampleCounter, gyroSample, accelSample);
            mH2tstatus.setText(results + System.getProperty("line.separator") + fs.reason);
        }
        closeCaptureStream();
        toneGen1 = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        toneGen1.startTone(ToneGenerator.TONE_DTMF_1, 200);
        toneGen1.startTone(ToneGenerator.TONE_DTMF_2, 200);
        toneGen1.startTone(ToneGenerator.TONE_DTMF_3, 200);
        //mCaptureToFileChecked.setChecked(false);
    }

    private void setButtonStopStatus() {
        mStartPlotButton.setImageResource(R.drawable.ic_stop);
        mStartPlotButton.setContentDescription("Start");
    }

    private void setButtonStartStatus() {
        mStartPlotButton.setImageResource(R.drawable.ic_play_arrow);
        mStartPlotButton.setContentDescription("Stop");
    }

    private void h2tSummary() {
        mH2tstatus.setText("Step Detection Complete. You are a good walker!!!");
    }

    /**
     * call when the user click on the button, will start/ai_log_stop plotting the data for the selected
     * feature
     *
     * @param v clicked item (not used)
     */
    @Override
    public void onClick(View v) {
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        super.startActivityForResult(intent, requestCode);
        String csvFile = null;
        if (intent != null && intent.getData() != null) {
            csvFile = intent.getData().getPath();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        //  Handle activity result here
        List<String[]> inertialMeasurements;
        int sample = 0;
        double ms = 0;

        /***********
         * first setup X, Y , and Z lookup table based on GUI selections;
         *  in the matlab file, Gyroscope XYZ coordinates are at 0, 7, 4 respectively
         *   GyroscopeX_ds = Double.parseDouble(sArray[0]);
         *   GyroscopeY_ds = Double.parseDouble(sArray[7]);
         *   GyroscopeZ_ds = Double.parseDouble(sArray[4]);
         *                   *
         */
        int[] xyz_gyro = {0, 7, 4};
        String[] xyz = {"X", "Y", "Z"};
        int xGyroIndex = xyz_gyro[Xcoord];
        int yGyroIndex = xyz_gyro[Ycoord];
        int zGyroIndex = xyz_gyro[Zcoord];
        double GyroscopeX_ds = 0;
        double GyroscopeY_ds = 0;
        double GyroscopeZ_ds = 0;
        StepResults stepResults = new StepResults();

        super.onActivityResult(requestCode, resultCode, intent);

        if (intent != null && intent.getData() != null) {
            Uri content_describer = intent.getData();
            if (requestCode == WRITE_REQUEST_CODE) {
                switch (resultCode) {
                    case Activity.RESULT_OK:
                        try {
                            outputStream = contentResolver.openOutputStream(content_describer);
                            captureReady = true;
                        } catch (IOException e) {
                            mH2tstatus.setText("Error opening file");
                            closeCaptureStream();
                        }
                        folder.setText(dataFilename);
                        break;
                    case Activity.RESULT_CANCELED:
                        mH2tstatus.setText("Canceled");
                        folder.setText("");
                        closeCaptureStream();
                        break;
                }
            }
        }
    }

    /**
     * after a screen rotation the gui item are recreated so we have to restore the status
     * as before the the rotation, this method is called only if we rotate the screen when we are
     * plotting something
     */
    private void restoreGui() {
        //restore the plot
        //restoreChart();
        //we are plotting something -> change the button label
        setButtonStopStatus();
    }

    private static List<Class<? extends Feature>> getSupportedFeatures() {
        Class<? extends Feature>[] temp =
                PlotFeatureFragment.class.getAnnotation(DemoDescriptionAnnotation.class).requareOneOf();

        return Arrays.asList(temp);
    }

    /**
     * we enable the button for start plotting the data
     *
     * @param node node where the notification will be enabled
     */
    @Override
    protected void enableNeededNotification(@NonNull final Node node) {
        //run
        updateGui(() -> {
            mStartPlotButton.setEnabled(true);
        });
    }

    @Override
    protected void disableNeedNotification(@NonNull Node node) {
    }

    ///// FeatureAutoConfigurationListener//////

    /**
     * move the view in a calibrate state
     */
    private void startDataStream(){
       // if(mFeature!=null)
       //     mFeature.startAutoConfiguration();
    }

    /**
     * move the view in a uncalibrate state
     */
    private void stopDataStream(){
        //if(mFeature!=null)
        //    mFeature.stopAutoConfiguration();
    }

    @Override
    public void onAutoConfigurationStarting(FeatureAutoConfigurable f) {
        stopDataStream();
    }

    @Override
    public void onConfigurationFinished(FeatureAutoConfigurable f, int status) {
        startDataStream();
    }

    @Override
    public void onAutoConfigurationStatusChanged(FeatureAutoConfigurable f, int status) {
        if(status == 0 ){ // uncalibrated
            stopDataStream();
        }else if (status == 100){ //fully calibrated
            startDataStream();
        }
    }
    @Override
    public void onUpdate(Feature f, Feature.Sample sample) { }
}
