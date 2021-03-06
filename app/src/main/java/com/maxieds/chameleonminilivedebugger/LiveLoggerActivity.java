package com.maxieds.chameleonminilivedebugger;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.support.annotation.ColorInt;
import android.support.annotation.DrawableRes;
import android.support.design.widget.TabLayout;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toolbar;

import com.crashlytics.android.Crashlytics;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

import io.fabric.sdk.android.Fabric;

import static com.maxieds.chameleonminilivedebugger.TabFragment.TAB_EXPORT;
import static com.maxieds.chameleonminilivedebugger.TabFragment.TAB_LOG;
import static com.maxieds.chameleonminilivedebugger.TabFragment.TAB_LOG_TOOLS;
import static com.maxieds.chameleonminilivedebugger.TabFragment.TAB_SEARCH;
import static com.maxieds.chameleonminilivedebugger.TabFragment.TAB_TOOLS;

/**
 * <h1>Live Logger Activity</h1>
 * Implementation of the main running activity in the application.
 *
 * @author  Maxie D. Schmidt
 * @since   12/31/17
 */
public class LiveLoggerActivity extends AppCompatActivity {

    private static final String TAG = LiveLoggerActivity.class.getSimpleName();

    /**
     * We assume there is only one instance of the singleton activity running at a time.
     */
    public static LiveLoggerActivity runningActivity = null;
    Bundle localSavedInstanceState;

    /**
     * Static variables used across classes.
     */
    public static LayoutInflater defaultInflater;
    public static Context defaultContext;
    public static LinearLayout logDataFeed;
    public static List<LogEntryBase> logDataEntries = new ArrayList<LogEntryBase>();
    public static int RECORDID = 0;
    public static boolean logDataFeedConfigured = false;
    public static SpinnerAdapter spinnerRButtonAdapter;
    public static SpinnerAdapter spinnerRButtonLongAdapter;
    public static SpinnerAdapter spinnerLButtonAdapter;
    public static SpinnerAdapter spinnerLButtonLongAdapter;
    public static SpinnerAdapter spinnerLEDRedAdapter;
    public static SpinnerAdapter spinnerLEDGreenAdapter;
    public static SpinnerAdapter spinnerLogModeAdapter;
    public static SpinnerAdapter spinnerCmdShellAdapter;
    public static MenuItem selectedThemeMenuItem;
    public static boolean userIsScrolling = false;
    private static ViewPager viewPager;
    private static int selectedTab = TAB_LOG;

    /**
     * Configuration of the USB serial port.
     */
    public static UsbSerialDevice serialPort;
    public static final Semaphore serialPortLock = new Semaphore(1, true);
    boolean usbReceiversRegistered = false;
    public static final int USB_DATA_BITS = 16;

    /**
     * Appends a new log to the logging interface tab.
     * @param logEntry
     * @see LogEntryUI
     * @see LogEntryMetadataRecord
     */
    public static void appendNewLog(LogEntryBase logEntry) {
        if(LiveLoggerActivity.selectedTab != TAB_LOG) {
            if(logEntry instanceof LogEntryUI)
                runningActivity.setStatusIcon(R.id.statusIconNewXFer, R.drawable.statusxfer16);
            else
                runningActivity.setStatusIcon(R.id.statusIconNewMsg, R.drawable.statusnewmsg16);
        }
        logDataFeed.addView(logEntry.getLayoutContainer());
        logDataEntries.add(logEntry);
    }

    /**
     * Sets one of the small status icons indicated at the top of the activity window.
     * @param iconID
     * @param iconDrawable
     * @ref R.id.statusIconUSB
     * @ref R.id.statusIconUlDl
     * @ref R.id.statusIconNewMsg
     * @ref R.id.statusIconNewXFer
     */
    public void setStatusIcon(int iconID, int iconDrawable) {
        ((ImageView) findViewById(iconID)).setAlpha(255);
        ((ImageView) findViewById(iconID)).setImageDrawable(getResources().getDrawable(iconDrawable));
    }

    /**
     * Obtains the color associated with the theme.
     * @param attrID
     * @return
     */
    @ColorInt
    public int getThemeColorVariant(int attrID) {
        return getTheme().obtainStyledAttributes(new int[] {attrID}).getColor(0, attrID);
    }

    @DrawableRes
    public Drawable getThemeDrawableVariant(int attrID) {
        return getTheme().obtainStyledAttributes(new int[] {attrID}).getDrawable(0);
    }

    /**
     * Clears the corresponding status icon indicated at the top of the activity window.
     * @param iconID
     * @ref R.id.statusIconUSB
     * @ref R.id.statusIconUlDl
     * @ref R.id.statusIconNewMsg
     * @ref R.id.statusIconNewXFer
     */
    public void clearStatusIcon(int iconID) {
        ((ImageView) findViewById(iconID)).setAlpha(0);
    }

    /**
     * Initializes the activity state and variables.
     * Called when the activity is created.
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // fix bug where the tabs are blank when the application is relaunched:
        if(runningActivity == null || !isTaskRoot()) {
            super.onCreate(savedInstanceState);
            if(!BuildConfig.DEBUG)
                Fabric.with(this, new Crashlytics());
        }
        if(!isTaskRoot()) {
            Log.w(TAG, "ReLaunch Intent Action: " + getIntent().getAction());
            final Intent intent = getIntent();
            final String intentAction = intent.getAction();
            if (intentAction != null && (intentAction.equals(UsbManager.ACTION_USB_DEVICE_DETACHED) || intentAction.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED))) {
                Log.w(TAG, "onCreate(): Main Activity is not the root.  Finishing Main Activity instead of re-launching.");
                finish();
                ChameleonIO.USB_CONFIGURED = false;
                //LiveLoggerActivity.runningActivity.onNewIntent(intent);
                return;
            }
        }
        if (getIntent().getBooleanExtra("EXIT", false)) {
            finish();
            return;
        }

        boolean completeRestart = (runningActivity == null);
        runningActivity = this;
        localSavedInstanceState = savedInstanceState;
        defaultInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        defaultContext = getApplicationContext();

        if(completeRestart) {
            SharedPreferences preferences = getSharedPreferences(LiveLoggerActivity.TAG, Context.MODE_PRIVATE);
            String storedAppTheme = preferences.getString("ThemeUI", "Standard Green");
            setLocalTheme(storedAppTheme);
        }
        setContentView(R.layout.activity_live_logger);

        Toolbar actionBar = (Toolbar) findViewById(R.id.toolbarActionBar);
        actionBar.setSubtitle("Portable logging interface v" + String.valueOf(BuildConfig.VERSION_NAME));
        if (BuildConfig.PAID_APP_VERSION)
            actionBar.inflateMenu(R.menu.paid_theme_menu);
        else
            actionBar.inflateMenu(R.menu.main_menu);
        setActionBar(actionBar);
        clearStatusIcon(R.id.statusIconUlDl);
        getWindow().setTitleColor(getThemeColorVariant(R.attr.actionBarBackgroundColor));
        getWindow().setStatusBarColor(getThemeColorVariant(R.attr.colorPrimaryDark));
        getWindow().setNavigationBarColor(getThemeColorVariant(R.attr.colorPrimaryDark));

        configureTabViewPager();

        if(completeRestart) {
            String[] permissions = {
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.INTERNET",
                    "com.android.example.USB_PERMISSION"
            };
            if (android.os.Build.VERSION.SDK_INT >= 23)
                requestPermissions(permissions, 200);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR); // keep app from crashing when the screen rotates
        }

        if(!usbReceiversRegistered) {
            serialPort = configureSerialPort(null, usbReaderCallback);
            if (serialPort != null)
                ChameleonIO.deviceStatus.updateAllStatusAndPost(true);

            BroadcastReceiver usbActionReceiver = new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction() != null && (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED) || intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED))) {
                        onNewIntent(intent);
                    }
                }
            };
            IntentFilter usbActionFilter = new IntentFilter();
            usbActionFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            usbActionFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(usbActionReceiver, usbActionFilter);
            usbReceiversRegistered = true;
        }

        String userGreeting = "Dear new user, \n\nThank you for using and testing this app. It is still under development. We are working to fix any new errors" +
                " on untested devices using Crashlytics. While this will eventually fix most unforseen errors that slip through testing, *PLEASE* " +
                "if you consistently get a runtime error using a feature notify the developer at maxieds@gmail.com so it can be fixed quickly for all users.\n\n" +
                "Enjoy the app and using your Chameleon Mini device!";
        appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("STATUS", userGreeting));

        clearStatusIcon(R.id.statusIconNewMsg);
        clearStatusIcon(R.id.statusIconNewXFer);

    }

    private static ViewPager.OnPageChangeListener tabChangeListener = null;

    /**
     * Configures the tabs part of the main UI.
     * @ref onCreate
     * @ref onOptionsItemSelected
     */
    protected void configureTabViewPager() {

        logDataFeedConfigured = false;
        logDataFeed = new LinearLayout(getApplicationContext());
        if(logDataEntries != null)
            logDataEntries.clear();

        viewPager = (ViewPager) findViewById(R.id.tab_pager);
        TabFragmentPagerAdapter tfPagerAdapter = new TabFragmentPagerAdapter(getSupportFragmentManager(), LiveLoggerActivity.this);
        viewPager.setAdapter(tfPagerAdapter);
        if(tabChangeListener != null) {
            viewPager.removeOnPageChangeListener(tabChangeListener);
        }
        tabChangeListener = new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            }

            @Override
            public void onPageSelected(int position) {
                LiveLoggerActivity.selectedTab = position;
                switch (position) {
                    case TAB_LOG:
                        LiveLoggerActivity.runningActivity.clearStatusIcon(R.id.statusIconNewMsg);
                        LiveLoggerActivity.runningActivity.clearStatusIcon(R.id.statusIconNewXFer);
                        LiveLoggerActivity.runningActivity.clearStatusIcon(R.id.statusIconUlDl);
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        };
        viewPager.addOnPageChangeListener(tabChangeListener);

        TabLayout tabLayout = (TabLayout) findViewById(R.id.tab_layout);
        tabLayout.removeAllTabs();
        tabLayout.addTab(tabLayout.newTab().setText(tfPagerAdapter.getPageTitle(TAB_LOG)));
        tabLayout.addTab(tabLayout.newTab().setText(tfPagerAdapter.getPageTitle(TAB_TOOLS)));
        tabLayout.addTab(tabLayout.newTab().setText(tfPagerAdapter.getPageTitle(TAB_LOG_TOOLS)));
        tabLayout.addTab(tabLayout.newTab().setText(tfPagerAdapter.getPageTitle(TAB_EXPORT)));
        tabLayout.addTab(tabLayout.newTab().setText(tfPagerAdapter.getPageTitle(TAB_SEARCH)));
        tabLayout.setupWithViewPager(viewPager);

        viewPager.setOffscreenPageLimit(TabFragmentPagerAdapter.TAB_COUNT - 1);
        viewPager.setCurrentItem(selectedTab);
        tfPagerAdapter.notifyDataSetChanged();

        // the view pager hides the tab icons by default, so we reset them:
        tabLayout.getTabAt(TAB_LOG).setIcon(R.drawable.nfc24v1);
        tabLayout.getTabAt(TAB_TOOLS).setIcon(R.drawable.tools24);
        tabLayout.getTabAt(TAB_LOG_TOOLS).setIcon(R.drawable.logtools24);
        tabLayout.getTabAt(TAB_EXPORT).setIcon(R.drawable.insertbinary24);
        tabLayout.getTabAt(TAB_SEARCH).setIcon(R.drawable.binarysearch24v2);

    }

    /**
     * Sets up the themes changer menu in the paid flavor of the app.
     * @param overflowMenu
     * @return
     */
    @Override
    public boolean onCreateOptionsMenu(Menu overflowMenu) {
        if(BuildConfig.PAID_APP_VERSION)
            getMenuInflater().inflate(R.menu.paid_theme_menu, overflowMenu);
        else
            getMenuInflater().inflate(R.menu.main_menu, overflowMenu);
        return true;
    }

    /**
     * Sets the local theme (before the ful UI updating to implement the theme change) based on
     * the passed theme text description.
     * @param themeDesc
     * @ref res/values/style.xml
     */
    private void setLocalTheme(String themeDesc) {
        int themeID;
        switch(themeDesc) {
            case "Amber":
                themeID = R.style.AppThemeAmber;
                break;
            case "Atlanta":
                themeID = R.style.AppThemeAtlanta;
                break;
            case "Black":
                themeID = R.style.AppThemeBlack;
                break;
            case "Blue":
                themeID = R.style.AppThemeBlue;
                break;
            case "Chocolate":
                themeID = R.style.AppThemeChocolate;
                break;
            case "Chicky":
                themeID = R.style.AppThemeChicky;
                break;
            case "Goldenrod":
                themeID = R.style.AppThemeGoldenrod;
                break;
            case "Standard Green":
                themeID = R.style.AppThemeGreen;
                break;
            case "Lightblue":
                themeID = R.style.AppThemeLightblue;
                break;
            case "Linux Green On Black":
                themeID = R.style.AppThemeLinuxGreenOnBlack;
                break;
            case "Purple":
                themeID = R.style.AppThemePurple;
                break;
            case "Red":
                themeID = R.style.AppThemeRed;
                break;
            case "Redmond":
                themeID = R.style.AppThemeRedmond;
                break;
            case "Teal":
                themeID = R.style.AppThemeTeal;
                break;
            case "Urbana Desfire":
                themeID = R.style.AppThemeUrbanaDesfire;
                break;
            case "White":
                themeID = R.style.AppThemeWhite;
                break;
            case "Winter":
                themeID = R.style.AppThemeWinter;
                break;
            default:
                themeID = R.style.AppThemeGreen;
        }
        Log.w(TAG, themeDesc);
        Log.w(TAG, String.valueOf(themeID));
        setTheme(themeID);
    }

    /**
     * Handles the new theme selections in the paid flavor of the app.
     * @param mitem
     * @return
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem mitem) {
        String themeDesc = mitem.getTitle().toString().substring("Theme: ".length());
        setLocalTheme(themeDesc);

        // store the theme setting for when the app reopens:
        SharedPreferences sharedPrefs = getSharedPreferences(LiveLoggerActivity.TAG, Context.MODE_PRIVATE);
        SharedPreferences.Editor spEditor = sharedPrefs.edit();
        spEditor.putString("ThemeUI", themeDesc);
        spEditor.commit();

        // finally, apply the theme settings by (essentially) restarting the activity UI:
        onCreate(localSavedInstanceState);

        if(selectedThemeMenuItem != null) {
            selectedThemeMenuItem.setIcon(R.drawable.thememarker24);
        }
        mitem.setChecked(true);
        mitem.setEnabled(true);
        mitem.setIcon(R.drawable.themecheck24);

        appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("THEME", "New theme installed: " + themeDesc));
        return true;
    }

    /**
     * Handles newly attached / detached USB devices.
     * @param intent
     */
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if(intent == null)
            return;
        else if(intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            if(serialPort != null && ChameleonIO.USB_CONFIGURED)
                return;
            ChameleonIO.deviceStatus.statsUpdateHandler.removeCallbacks(ChameleonIO.deviceStatus.statsUpdateRunnable);
            closeSerialPort(serialPort);
            serialPort = configureSerialPort(null, usbReaderCallback);
            LiveLoggerActivity.runningActivity.actionButtonRestorePeripheralDefaults(null);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(true);
            ChameleonIO.USB_CONFIGURED = true;
        }
        else if(intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
            ChameleonIO.deviceStatus.statsUpdateHandler.removeCallbacks(ChameleonIO.deviceStatus.statsUpdateRunnable);
            if(ChameleonIO.WAITING_FOR_RESPONSE)
                ChameleonIO.WAITING_FOR_RESPONSE = false;
            closeSerialPort(serialPort);
            ChameleonIO.USB_CONFIGURED = false;
        }
    }

    /**
     * Called when the activity is paused or put into the background.
     * @ref onResume()
     */
    @Override
    public void onPause() {
        super.onPause();
        // clear the status icon before the application is abruptly terminated:
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(1);
    }

    /**
     * Called when the activity is resumes or put into the foreground.
     * @ref onPause()
     */
    @Override
    public void onResume() {
        super.onResume();
        // setup the system status bar icon:
        NotificationCompat.Builder statusBarIconBuilder = new NotificationCompat.Builder(this);
        statusBarIconBuilder.setSmallIcon(R.drawable.chameleonstatusbaricon16);
        statusBarIconBuilder.setContentTitle(getResources().getString(R.string.app_name) + " -- v" + BuildConfig.VERSION_NAME);
        statusBarIconBuilder.setContentText(getResources().getString(R.string.app_desc));
        //statusBarIconBuilder.setVibrate(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
        statusBarIconBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        Intent resultIntent = new Intent(this, LiveLoggerActivity.class);
        PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        statusBarIconBuilder.setContentIntent(resultPendingIntent);
        Notification notification = statusBarIconBuilder.build();
        notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        NotificationManager notifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifyMgr.notify(1, notification);
    }

    /**
     * Queries the Chameleon device with the query command and returns its response
     * (sans the preceeding ascii status code).
     * @param cmPort
     * @param query
     * @return String device response
     * @ref ChameleonIO.DEVICE_RESPONSE
     * @ref ChameleonIO.DEVICE_RESPONSE_CODE
     * @ref LiveLoggerActivity.usbReaderCallback
     */
    public static String getSettingFromDevice(UsbSerialDevice cmPort, String query) {
        ChameleonIO.DEVICE_RESPONSE = "0";
        ChameleonIO.LASTCMD = query;
        if(cmPort == null)
            return ChameleonIO.DEVICE_RESPONSE;
        ChameleonIO.WAITING_FOR_RESPONSE = true;
        ChameleonIO.SerialRespCode rcode = ChameleonIO.executeChameleonMiniCommand(cmPort, query, ChameleonIO.TIMEOUT);
        for(int i = 0; i < ChameleonIO.TIMEOUT / 50; i++) {
            if(!ChameleonIO.WAITING_FOR_RESPONSE)
                break;
            try {
                Thread.sleep(50);
            } catch(InterruptedException ie) {
                ChameleonIO.WAITING_FOR_RESPONSE = false;
                break;
            }
        }
        return ChameleonIO.DEVICE_RESPONSE;
    }

    /**
     * Establishes the connection between the application and the Chameleon device.
     * @param serialPort
     * @param readerCallback
     * @return the configured serial port (or null on error)
     */
    public UsbSerialDevice configureSerialPort(UsbSerialDevice serialPort, UsbSerialInterface.UsbReadCallback readerCallback) {

        if(serialPort != null)
            closeSerialPort(serialPort);

        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice device = null;
        UsbDeviceConnection connection = null;
        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
        if(usbDevices != null && !usbDevices.isEmpty()) {
            for(Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                device = entry.getValue();
                if(device == null)
                    continue;
                int deviceVID = device.getVendorId();
                int devicePID = device.getProductId();
                if(deviceVID == ChameleonIO.CMUSB_VENDORID && devicePID == ChameleonIO.CMUSB_PRODUCTID) {
                    connection = usbManager.openDevice(device);
                    break;
                }
            }
        }
        if(device == null || connection == null) {
            appendNewLog(new LogEntryMetadataRecord(defaultInflater, "USB STATUS: ", "Connection to device unavailable."));
            serialPort = null;
            setStatusIcon(R.id.statusIconUSB, R.drawable.usbdisconnected16);
            return serialPort;
        }
        serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
        if(serialPort != null && serialPort.open()) {
            //serialPort.setBaudRate(115200);
            serialPort.setBaudRate(256000);
            //serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
            serialPort.setDataBits(USB_DATA_BITS); // slight optimization? ... yes, better
            serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
            serialPort.setParity(UsbSerialInterface.PARITY_NONE);
            serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
            serialPort.read(readerCallback);
        }
        else {
            appendNewLog(new LogEntryMetadataRecord(defaultInflater, "USB ERROR: ", "Unable to configure serial device."));
            serialPort = null;
            setStatusIcon(R.id.statusIconUSB, R.drawable.usbdisconnected16);
            return serialPort;
        }

        ChameleonIO.setLoggerConfigMode(serialPort, ChameleonIO.TIMEOUT);
        //ChameleonIO.setReaderConfigMode(serialPort, ChameleonIO.TIMEOUT);
        ChameleonIO.enableLiveDebugging(serialPort, ChameleonIO.TIMEOUT);
        ChameleonIO.PAUSED = false;
        String usbDeviceData = String.format(Locale.ENGLISH, "Manufacturer: %s\nProduct Name: %s\nVersion: %s\nDevice Serial: %s\nUSB Dev: %s",
                device.getManufacturerName(), device.getProductName(), device.getVersion(), device.getSerialNumber(), device.getDeviceName());
        appendNewLog(new LogEntryMetadataRecord(defaultInflater, "USB STATUS: ", "Successfully configured the device in passive logging mode...\n" + usbDeviceData));
        setStatusIcon(R.id.statusIconUSB, R.drawable.usbconnected16);
        return serialPort;

    }

    /**
     * Closes the connection between the application and the Chameleon device.
     * @param serialPort
     * @return boolean success of operation (true)
     */
    public boolean closeSerialPort(UsbSerialDevice serialPort) {
        if(serialPort != null)
            serialPort.close();
        ChameleonIO.PAUSED = true;
        ExportTools.EOT = true;
        ExportTools.transmissionErrorOccurred = true;
        ChameleonIO.DOWNLOAD = false;
        ChameleonIO.UPLOAD = false;
        ChameleonIO.WAITING_FOR_XMODEM = false;
        ChameleonIO.WAITING_FOR_RESPONSE = false;
        ChameleonIO.EXPECTING_BINARY_DATA = false;
        ChameleonIO.LASTCMD = "";
        setStatusIcon(R.id.statusIconUSB, R.drawable.usbdisconnected16);
        return true;
    }

    /**
     * Sets up the handling of the serial data responses received from the device
     * (command responses and spontaneous LIVE log data).
     */
    public UsbSerialInterface.UsbReadCallback usbReaderCallback = new UsbSerialInterface.UsbReadCallback() {
        // this is what's going to get called when the LIVE config spontaneously prints its log data to console:
        @Override
        public void onReceivedData(byte[] liveLogData) {
            //Log.i(TAG, "USBReaderCallback Received Data: " + Utils.bytes2Hex(liveLogData));
            //Log.i(TAG, "    => " + Utils.bytes2Ascii(liveLogData));
            if(ChameleonIO.PAUSED) {
                return;
            }
            else if(ChameleonIO.DOWNLOAD) {
                //Log.i(TAG, "USBReaderCallback / DOWNLOAD");
                ExportTools.performXModemSerialDownload(liveLogData);
                return;
            }
            else if(ChameleonIO.UPLOAD) {
                //Log.i(TAG, "USBReaderCallback / UPLOAD");
                ExportTools.performXModemSerialUpload(liveLogData);
                return;
            }
            else if(ChameleonIO.WAITING_FOR_XMODEM) {
                //Log.i(TAG, "USBReaderCallback / WAITING_FOR_XMODEM");
                String strLogData = new String(liveLogData);
                if(strLogData.length() >= 11 && strLogData.substring(0, 11).equals("110:WAITING")) {
                    ChameleonIO.WAITING_FOR_XMODEM = false;
                    return;
                }
            }
            else if(ChameleonIO.WAITING_FOR_RESPONSE && ChameleonIO.isCommandResponse(liveLogData)) {
                String strLogData = new String(liveLogData);
                //Log.i(TAG, strLogData);
                ChameleonIO.DEVICE_RESPONSE_CODE = strLogData.split("[\n\r]+")[0];
                ChameleonIO.DEVICE_RESPONSE = strLogData.replace(ChameleonIO.DEVICE_RESPONSE_CODE, "").replaceAll("[\n\r]*", "");
                if(ChameleonIO.EXPECTING_BINARY_DATA) {
                    int binaryBufSize = liveLogData.length - ChameleonIO.DEVICE_RESPONSE_CODE.length() - 2;
                    ChameleonIO.DEVICE_RESPONSE_BINARY = new byte[binaryBufSize];
                    System.arraycopy(liveLogData, liveLogData.length - binaryBufSize, ChameleonIO.DEVICE_RESPONSE_BINARY, 0, binaryBufSize);
                    ChameleonIO.EXPECTING_BINARY_DATA = false;
                }
                ChameleonIO.WAITING_FOR_RESPONSE = false;
                return;
            }
            final LogEntryUI nextLogEntry = LogEntryUI.newInstance(liveLogData, "");
            if(nextLogEntry != null) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        appendNewLog(nextLogEntry);
                    }
                });
            }
        }
    };

    /**
     * Exits the application.
     * @param view
     * @see res/layout/activity_live_logger.xml
     */
    public void actionButtonExit(View view) {
        ChameleonIO.deviceStatus.statsUpdateHandler.removeCallbacks(ChameleonIO.deviceStatus.statsUpdateRunnable);
        closeSerialPort(serialPort);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(1);
        finish();
    }

    /**
     * Queries and restores the current defaults of the device peripheral actions indicated in the
     * Tools Menu spinners.
     * @param view
     * @see res/layout/tools_menu_tab.xml
     */
    public void actionButtonRestorePeripheralDefaults(View view) {
            if (LiveLoggerActivity.serialPort != null) {
                // next, query the defaults from the device to get accurate settings (if the device is connected):
                int[] spinnerIDs = {
                        R.id.RButtonSpinner,
                        R.id.RButtonLongSpinner,
                        R.id.LButtonSpinner,
                        R.id.LButtonLongSpinner,
                        R.id.LEDRedSpinner,
                        R.id.LEDGreenSpinner
                };
                String[] queryCmds = {
                        "RBUTTON?",
                        "RBUTTON_LONG?",
                        "LBUTTON?",
                        "LBUTTON_LONG?",
                        "LEDRED?",
                        "LEDGREEN?"
                };
                for (int i = 0; i < spinnerIDs.length; i++) {
                    Log.i(TAG, queryCmds[i]);
                    Spinner curSpinner = (Spinner) LiveLoggerActivity.runningActivity.findViewById(spinnerIDs[i]);
                    String deviceSetting = getSettingFromDevice(LiveLoggerActivity.serialPort, queryCmds[i]);
                    curSpinner.setSelection(((ArrayAdapter<String>) curSpinner.getAdapter()).getPosition(deviceSetting));
                }
        }

    }

    /**
     * Clears the text appended to certain commands run from the Tools Menu.
     * @param view
     * @ref R.id.userInputFormattedBytes
     * @see res/layout/tools_menu_tab.xml
     */
    public void actionButtonClearUserText(View view) {
        TextView userInputText = (TextView) findViewById(R.id.userInputFormattedBytes);
        userInputText.setText("");
        userInputText.setHint("01 23 45 67 89 ab cd ef");
    }

    /**
     * Manual refreshing of the device status settings requested by the user on button press at the
     * top right (second rightmost button) of the activity window.
     * @param view
     */
    public void actionButtonRefreshDeviceStatus(View view) {
        ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
    }

    /**
     * Clears all logging data from the Log tab.
     * @param view
     */
    public void actionButtonClearAllLogs(View view) {
        if(RECORDID > 0) {
            logDataEntries.clear();
            RECORDID = 0;
            logDataFeed.removeAllViewsInLayout();
        }
    }

    /**
     * Removes repeated log entries in sequential order in the logging tab.
     * Useful for pretty-fying / cleaning up the log entries when a device posts repeated
     * APDU command requests, or zero bits.
     * @param view
     */
    public void actionButtonCollapseSimilar(View view) {
        if(RECORDID == 0)
            return;
        byte[] curBits = null;
        boolean newBits = true;
        for(int v = 0; v < logDataEntries.size(); v++) {
            LogEntryBase lde = logDataEntries.get(v);
            if(lde instanceof LogEntryMetadataRecord) {
                newBits = true;
                continue;
            }
            else if(lde instanceof LogEntryUI && newBits) {
                byte[] nextDataPattern = ((LogEntryUI) lde).getEntryData();
                curBits = new byte[nextDataPattern.length];
                System.arraycopy(nextDataPattern, 0, curBits, 0, nextDataPattern.length);
                newBits = false;
            }
            else if(Arrays.equals(curBits, ((LogEntryUI) lde).getEntryData())) {
                logDataFeed.getChildAt(v).setVisibility(LinearLayout.GONE);
            }
            else {
                newBits = true;
            }
        }
    }

    /**
     * Handles button presses for most of the commands implemented in the Tools Menu.
     * @param view calling Button
     */
    public void actionButtonCreateNewEvent(View view) {
        if(serialPort == null) {
            appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("ERROR", "Cannot run command since USB is not configured."));
            return;
        }
        String createCmd = ((Button) view).getText().toString();
        String msgParam = "";
        if(createCmd.equals("READER")) {
            ChameleonIO.setReaderConfigMode(serialPort, ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("SNIFFER")) {
            ChameleonIO.setLoggerConfigMode(serialPort, ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("ULTRALIGHT")) {
            ChameleonIO.executeChameleonMiniCommand(serialPort, "CONFIG=MF_ULTRALIGHT", ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("CLASSIC-1K")) {
            ChameleonIO.executeChameleonMiniCommand(serialPort, "CONFIG=MF_CLASSIC_1K", ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("CLASSIC-4K")) {
            ChameleonIO.executeChameleonMiniCommand(serialPort, "CONFIG=MF_CLASSIC_4K", ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("CLASSIC-1K7B")) {
            ChameleonIO.executeChameleonMiniCommand(serialPort, "CONFIG=MF_CLASSIC_1K_7B", ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("CLASSIC-4K7B")) {
            ChameleonIO.executeChameleonMiniCommand(serialPort, "CONFIG=MF_CLASSIC_4K_7B", ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("MFU-EV1-80B")) {
            ChameleonIO.executeChameleonMiniCommand(serialPort, "CONFIG=MF_ULTRALIGHT_EV1_80B", ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("MFU-EV1-164B")) {
            ChameleonIO.executeChameleonMiniCommand(serialPort, "CONFIG=MF_ULTRALIGHT_EV1_164B", ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("CFGNONE")) {
            ChameleonIO.executeChameleonMiniCommand(serialPort, "CONFIG=NONE", ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
            return;
        }
        else if(createCmd.equals("RESET")) { // need to re-establish the usb connection:
            ChameleonIO.executeChameleonMiniCommand(serialPort, "RESET", ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.statsUpdateHandler.removeCallbacks(ChameleonIO.deviceStatus.statsUpdateRunnable);
            closeSerialPort(serialPort);
            configureSerialPort(null, usbReaderCallback);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(true);
            return;
        }
        else if(createCmd.equals("RANDOM UID")) {
            byte[] randomBytes = Utils.getRandomBytes(ChameleonIO.deviceStatus.UIDSIZE);
            String sendCmd = "UID=" + Utils.bytes2Hex(randomBytes).replace(" ", "");
            ChameleonIO.executeChameleonMiniCommand(serialPort, sendCmd, ChameleonIO.TIMEOUT);
            ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
        }
        else if(createCmd.equals("Log Replay")) {
            appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("STATUS", "RE: LOG REPLAY: This is a wishlist feature. It might be necessary to add it to the firmware and implement it in hardware. Not currently implemented."));
            return;
        }
        else if(createCmd.equals("STATUS") || createCmd.equals("NEW EVENT") ||
                createCmd.equals("ERROR") || createCmd.equals("LOCATION") ||
                createCmd.equals("CARD INFO")) {
            try {
                displayUserInputPrompt("Description of the new event? ");
                Looper.loop();
            }
            catch(RuntimeException msgReady) {}
            msgParam = userInputStack;
            userInputStack = null;
        }
        else if(createCmd.equals("ONCLICK")) {
            msgParam = "SYSTICK Millis := " + getSettingFromDevice(serialPort, "SYSTICK?");
        }
        else if(createCmd.equals("GETUID")) {
            String rParam = getSettingFromDevice(serialPort, "GETUID");
            msgParam = "GETUID: " + rParam;
        }
        else if(createCmd.equals("SEND") || createCmd.equals("SEND_RAW")) {
            String bytesToSend = ((TextView) findViewById(R.id.userInputFormattedBytes)).getText().toString().replaceAll(" ", "");
            if(bytesToSend.length() != 2 || !Utils.stringIsHexadecimal(bytesToSend)) {
                appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("ERROR", "Input to send to card must be a _single hexadecimal_ byte!"));
                return;
            }
            msgParam = "Card Response (if any): " + getSettingFromDevice(serialPort, createCmd + " " + bytesToSend);
        }
        else if(createCmd.equals("AUTOCAL")) {
            msgParam = getSettingFromDevice(serialPort, "AUTOCALIBRATE");
        }
        else {
            msgParam = getSettingFromDevice(serialPort, createCmd);
        }
        appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord(createCmd, msgParam));
    }

    /**
     * Highlights the selected logs (by checkmark in the Log tab) in the color of the passed button.
     * @param view pressed Button
     */
    public void actionButtonSelectedHighlight(View view) {
        int highlightColor = Color.parseColor(((Button) view).getTag().toString());
        for (int vi = 0; vi < logDataFeed.getChildCount(); vi++) {
            View logEntryView = logDataFeed.getChildAt(vi);
            if (logDataEntries.get(vi) instanceof LogEntryUI) {
                boolean isChecked = ((CheckBox) logEntryView.findViewById(R.id.entrySelect)).isChecked();
                if (isChecked)
                    logEntryView.setBackgroundColor(highlightColor);
            }
        }
    }

    /**
     * Unchecks all of the selected logs in the Log tab.
     * @param view
     */
    public void actionButtonUncheckAll(View view) {
        for (int vi = 0; vi < logDataFeed.getChildCount(); vi++) {
            View logEntryView = logDataFeed.getChildAt(vi);
            if (logDataEntries.get(vi) instanceof LogEntryUI) {
                ((CheckBox) logEntryView.findViewById(R.id.entrySelect)).setChecked(false);
            }
        }
    }

    /**
     * Used to mark whether the APDU response in the log is incoming / outgoing from
     * card <--> reader. Mostly reserved for future use as the Chameleon currently only logs responses
     * in one direction anyhow.
     * @param view
     */
    public void actionButtonSetSelectedXFer(View view) {

        int directionFlag = Integer.parseInt(((Button) view).getTag().toString());
        Drawable dirArrowIcon = getResources().getDrawable(R.drawable.xfer16);
        if(directionFlag == 1)
            dirArrowIcon = getResources().getDrawable(R.drawable.incoming16v2);
        else if(directionFlag == 2)
            dirArrowIcon = getResources().getDrawable(R.drawable.outgoing16v2);

        for (int vi = 0; vi < logDataFeed.getChildCount(); vi++) {
            View logEntryView = logDataFeed.getChildAt(vi);
            if (logDataEntries.get(vi) instanceof LogEntryUI) {
                boolean isChecked = ((CheckBox) logEntryView.findViewById(R.id.entrySelect)).isChecked();
                if (isChecked) {
                    ImageView xferMarker = (ImageView) logEntryView.findViewById(R.id.inputDirIndicatorImg);
                    xferMarker.setImageDrawable(dirArrowIcon);
                }
            }
        }

    }

    /**
     * Handles parsing of the buttons in the Logging Tools menu to be applied to all selected logs.
     * @param view pressed Button
     */
    public void actionButtonProcessBatch(View view) {
        String actionFlag = ((Button) view).getTag().toString();
        for (int vi = 0; vi < logDataFeed.getChildCount(); vi++) {
            View logEntryView = logDataFeed.getChildAt(vi);
            if (logDataEntries.get(vi) instanceof LogEntryUI) {
                boolean isChecked = ((CheckBox) logEntryView.findViewById(R.id.entrySelect)).isChecked();
                int recordIdx = ((LogEntryUI) logDataEntries.get(vi)).getRecordIndex();
                if (serialPort != null && isChecked && actionFlag.equals("SEND")) {
                    String byteString = ((LogEntryUI) logDataEntries.get(vi)).getPayloadData();
                    appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("CARD INFO", "Sending: " + byteString + "..."));
                    ChameleonIO.executeChameleonMiniCommand(serialPort, "SEND " + byteString, ChameleonIO.TIMEOUT);
                }
                else if(serialPort != null && isChecked && actionFlag.equals("SEND_RAW")) {
                    String byteString = ((LogEntryUI) logDataEntries.get(vi)).getPayloadData();
                    appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("CARD INFO", "Sending: " + byteString + "..."));
                    ChameleonIO.executeChameleonMiniCommand(serialPort, "SEND_RAW " + byteString, ChameleonIO.TIMEOUT);
                }
                else if(serialPort != null && isChecked && actionFlag.equals("CLONE_UID")) {
                    String uid = ((LogEntryUI) logDataEntries.get(vi)).getPayloadData();
                    if(uid.length() != 2 * ChameleonIO.deviceStatus.UIDSIZE) {
                        appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("ERROR", String.format("Number of bytes for record #%d != the required %d bytes!", recordIdx, ChameleonIO.deviceStatus.UIDSIZE)));
                    }
                    else {
                        ChameleonIO.executeChameleonMiniCommand(serialPort, "UID=" + uid, ChameleonIO.TIMEOUT);
                    }
                }
                else if(isChecked && actionFlag.equals("PRINT")) {
                    byte[] rawBytes = ((LogEntryUI) logDataEntries.get(vi)).getEntryData();
                    appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("PRINT", Utils.bytes2Hex(rawBytes) + "\n------\n" + Utils.bytes2Ascii(rawBytes)));
                }
                else if(isChecked && actionFlag.equals("HIDE")) {
                    logEntryView.setVisibility(View.GONE);
                }
                else if(isChecked && actionFlag.equals("COPY")) {
                    EditText etUserBytes = (EditText) findViewById(R.id.userInputFormattedBytes);
                    String appendBytes = Utils.bytes2Hex(((LogEntryUI) logDataEntries.get(vi)).getEntryData());
                    etUserBytes.append(appendBytes);
                }
            }
        }
    }

    public void actionButtonModifyUID(View view) {
        if(ChameleonIO.deviceStatus.UID == null || ChameleonIO.deviceStatus.UID.equals("DEVICE UID") || ChameleonIO.deviceStatus.UID.equals("NO UID."))
            return;
        String uidAction = ((Button) view).getTag().toString();
        byte[] uid = Utils.hexString2Bytes(ChameleonIO.deviceStatus.UID);
        int uidSize = uid.length - 1;
        if(uidAction.equals("INCREMENT_RIGHT"))
            uid[uidSize] += (byte) 0x01;
        else if(uidAction.equals("DECREMENT_RIGHT"))
            uid[uidSize] -= (byte) 0x01;
        else if(uidAction.equals("SHIFT_RIGHT")) {
            byte[] nextUID = new byte[uid.length];
            System.arraycopy(uid, 1, nextUID, 0, uid.length - 1);
            uid = nextUID;
        }
        else if(uidAction.equals("INCREMENT_LEFT"))
            uid[0] += (byte) 0x80;
        else if(uidAction.equals("DECREMENT_LEFT"))
            uid[0] -= (byte) 0x80;
        else if(uidAction.equals("SHIFT_LEFT")){
            byte[] nextUID = new byte[uid.length];
            System.arraycopy(uid, 0, nextUID, 1, uid.length - 1);
            uid = nextUID;
        }
        getSettingFromDevice(serialPort, String.format(Locale.ENGLISH, "UID=%s", Utils.bytes2Hex(uid).replace(" ", "")));
        ChameleonIO.deviceStatus.updateAllStatusAndPost(false);
        appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("UID", "Next device UID set to " + Utils.bytes2Hex(uid).replace(" ", ":")));
    }

    /**
     * Constructs and displays a dialog providing meta information about the application.
     * @param view
     * @ref R.string.aboutapp
     */
    public void actionButtonAboutTheApp(View view) {
        AlertDialog.Builder adBuilder = new AlertDialog.Builder(this, R.style.SpinnerTheme);
        String rawAboutStr = getString(R.string.apphtmlheader) + getString(R.string.aboutapp) + getString(R.string.apphtmlfooter);
        rawAboutStr = rawAboutStr.replace("%%ANDROID_VERSION_CODE%%", String.valueOf(BuildConfig.VERSION_CODE));
        rawAboutStr = rawAboutStr.replace("%%ANDROID_VERSION_NAME%%", String.valueOf(BuildConfig.VERSION_NAME));
        rawAboutStr = rawAboutStr.replace("%%ANDROID_FLAVOR_NAME%%", String.valueOf(BuildConfig.FLAVOR) + ", " + BuildConfig.BUILD_TIMESTAMP);
        rawAboutStr = rawAboutStr.replace("%%ABOUTLINKCOLOR%%", String.format(Locale.ENGLISH, "#%06X", 0xFFFFFF & getTheme().obtainStyledAttributes(new int[] {R.attr.colorAboutLinkColor}).getColor(0, getResources().getColor(R.color.colorBigVeryBadError))));
        //builder1.setMessage(Html.fromHtml(rawAboutStr, Html.FROM_HTML_MODE_LEGACY));

        WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(false);
        wv.loadDataWithBaseURL(null, rawAboutStr, "text/html", "UTF-8", "");
        wv.setBackgroundColor(getThemeColorVariant(R.attr.colorAccentHighlight));
        wv.getSettings().setLoadWithOverviewMode(true);
        wv.getSettings().setUseWideViewPort(true);
        //wv.getSettings().setBuiltInZoomControls(true);
        wv.getSettings().setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        wv.setInitialScale(10);

        adBuilder.setCancelable(true);
        adBuilder.setTitle("About the Application:");
        adBuilder.setIcon(R.drawable.chameleonicon_about64);
        adBuilder.setPositiveButton(
                "Done",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });
        adBuilder.setView(wv);
        AlertDialog alertDialog = adBuilder.create();

        alertDialog.show();

    }

    /**
     * Runs a command indicated in the TAG parameter of the pressed button.
     * @param view pressed Button
     */
    public void actionButtonRunCommand(View view) {
        String cmCmd = ((Button) view).getTag().toString();
        ChameleonIO.executeChameleonMiniCommand(serialPort, cmCmd, ChameleonIO.TIMEOUT);
    }

    /**
     * Wrapper around the first three buttons at the top of the Export tab for writing the
     * logs to Plaintext / HTML / native binary files formats.
     * @param view pressed Button
     */
    public void actionButtonWriteFile(View view) {
        LiveLoggerActivity.runningActivity.setStatusIcon(R.id.statusIconUlDl, R.drawable.statusdownload16);
        String fileType = ((Button) view).getTag().toString(), mimeType = "message/rfc822";
        String outfilePath = "logdata-" + Utils.getTimestamp().replace(":", "") + "." + fileType;
        File downloadsFolder = new File("//sdcard//Download//");
        File outfile = new File(downloadsFolder, outfilePath);
        boolean docsFolderExists = true;
        if (!downloadsFolder.exists()) {
            docsFolderExists = downloadsFolder.mkdir();
        }
        if (docsFolderExists) {
            outfile = new File(downloadsFolder.getAbsolutePath(),outfilePath);
        }
        else {
            appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("ERROR", "Unable to save output in Downloads folder."));
            LiveLoggerActivity.runningActivity.setStatusIcon(R.id.statusIconUlDl, R.drawable.statusxferfailed16);
            return;
        }
        try {
            outfile.createNewFile();
            if (fileType.equals("out")) {
                mimeType = "plain/text";
                ExportTools.writeFormattedLogFile(outfile);
            }
            else if (fileType.equals("html")) {
                mimeType = "text/html";
                ExportTools.writeHTMLLogFile(outfile);
            }
            else if (fileType.equals("bin")) {
                mimeType = "application/octet-stream";
                ExportTools.writeBinaryLogFile(outfile);
            }
        } catch(Exception ioe) {
            appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("ERROR", ioe.getMessage()));
            LiveLoggerActivity.runningActivity.setStatusIcon(R.id.statusIconUlDl, R.drawable.statusxferfailed16);
            ioe.printStackTrace();
            return;
        }
        DownloadManager downloadManager = (DownloadManager) defaultContext.getSystemService(DOWNLOAD_SERVICE);
        downloadManager.addCompletedDownload(outfile.getName(), outfile.getName(), true, "text/plain",
                outfile.getAbsolutePath(), outfile.length(),true);

        boolean saveFileChecked = ((RadioButton) findViewById(R.id.radio_save_storage)).isChecked();
        boolean emailFileChecked = ((RadioButton) findViewById(R.id.radio_save_email)).isChecked();
        boolean shareFileChecked = ((RadioButton) findViewById(R.id.radio_save_share)).isChecked();
        if(emailFileChecked || shareFileChecked) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mimeType);
            i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(outfile));
            i.putExtra(Intent.EXTRA_SUBJECT, "Chameleon Mini Log Data Output (Log Attached)");
            i.putExtra(Intent.EXTRA_TEXT, "See subject.");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share the file ... "));
        }
        appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("EXPORT", "Saved log file to \"" + outfilePath + "\"."));
    }

    /**
     * Called when the Export tab button for writing the DUMP_MFU command output is requested by the user.
     * @param view
     */
    public void actionButtonDumpMFU(View view) {
        ExportTools.saveBinaryDumpMFU("mfultralight");
    }

    /**
     * Called when one of the command Spinner buttons changes state.
     * @param view calling Spinner
     * @ref TabFragment.connectCommandListSpinnerAdapter
     * @ref TabFragment.connectPeripheralSpinnerAdapter
     */
    public static void actionSpinnerSetCommand(View view) {
        String sopt = ((Spinner) view).getSelectedItem().toString();
        if(sopt.substring(0, 2).equals("--"))
            sopt = "NONE";
        String cmCmd = ((Spinner) view).getTag().toString() + sopt;
        ChameleonIO.executeChameleonMiniCommand(serialPort, cmCmd, ChameleonIO.TIMEOUT);
    }

    /**
     * Listener object for new Spinner selections.
     * @ref LiveLoggerActivity.actionSpinnerSetCommand
     */
    public static AdapterView.OnItemSelectedListener itemSelectedListener = new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
            actionSpinnerSetCommand(arg1);
        }
        @Override
        public void onNothingSelected(AdapterView<?> arg0) {}
    };

    /**
     * Stores the user input for descriptions of the new annotation events available in the
     * Logging Tools tab.
     */
    private String userInputStack;

    /**
     * Prompts for a user description of the indicated annotation event from the
     * Log Tools tab.
     * @param promptMsg
     * @ref LiveLoggerActivity.userInputStack
     * @see res/layout/log_tools_tab.xml
     */
    public void displayUserInputPrompt(String promptMsg) {
        final EditText userInput = new EditText(this);
        userInput.setHint("What is the event description?");
        new AlertDialog.Builder(this)
                .setTitle(promptMsg)
                //.setMessage("Enter annotation for the current log.")
                .setView(userInput)
                .setPositiveButton("Submit Message", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        userInputStack = userInput.getText().toString();
                        throw new RuntimeException("The user input is ready.");
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                    }
                })
                .show();
    }

    /**
     * Wrapper around the button pressed for the download of stored log data and card information
     * by XModem in the Export tab.
     * @param view
     */
    public void actionButtonExportLogDownload(View view) {
        String action = ((Button) view).getTag().toString();
        if(action.equals("LOGDOWNLOAD"))
            ExportTools.downloadByXModem("LOGDOWNLOAD", "devicelog", false);
        else if(action.equals("LOGDOWNLOAD2LIVE"))
            ExportTools.downloadByXModem("LOGDOWNLOAD", "devicelog", true);
        else if(action.equals("DOWNLOAD"))
            ExportTools.downloadByXModem("DOWNLOAD", "carddata-" + ChameleonIO.deviceStatus.CONFIG, false);
    }

    /**
     * Constant for the file chooser dialog in the upload card data process.
     */
    private static final int FILE_SELECT_CODE = 0;

    /**
     * Called after the user chooses a file in the upload card dialog.
     * @param requestCode
     * @param resultCode
     * @param data
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case FILE_SELECT_CODE:
                if (resultCode == RESULT_OK) {
                    String filePath = "<FileNotFound>";
                    Cursor cursor = getContentResolver().query(data.getData(), null, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        filePath = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                        filePath = "//sdcard//Download//" + filePath;
                    }
                    throw new RuntimeException(filePath);
                }
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Wrapper around the card upload feature.
     * The method has the user pick a saved card file from the /sdcard/Download/* folder, then
     * initiates the upload with the function in ExportTools.
     * @param view pressed Button
     */
    public void actionButtonUploadCard(View view) {
        if(serialPort == null)
            return;
        // should potentially fix a slight "bug" where the card uploads but fails to get transferred to the
        // running device profile due to differences in the current configuration's memsize setting.
        // This might be more of a bug with the Chameleon software, but not entirely sure.
        // Solution: Clear out the current setting slot to CONFIG=NONE before performing the upload:
        //getSettingFromDevice(serialPort, "CONFIG=NONE");

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setDataAndType(Uri.parse("//sdcard//Download//"), "*/*");
        try {
            startActivityForResult(Intent.createChooser(intent, "Select a Card File to Upload"), FILE_SELECT_CODE);
        } catch (android.content.ActivityNotFoundException e) {
            appendNewLog(LogEntryMetadataRecord.createDefaultEventRecord("ERROR", "Unable to choose card file: " + e.getMessage()));
        }
        String cardFilePath = "";
        try {
            Looper.loop();
        } catch(RuntimeException rte) {
            cardFilePath = rte.getMessage().split("java.lang.RuntimeException: ")[1];
            Log.i(TAG, "Chosen Card File: " + cardFilePath);
        }
        ExportTools.uploadCardFileByXModem(cardFilePath);
    }

    // TODO: javadcocs
    public void actionButtonPerformSearch(View view) {

        // hide the search keyboard obstructing the results after the button press:
        View focusView = this.getCurrentFocus();
        if (focusView != null) {
            InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        long startTime = System.currentTimeMillis();

        // clear out the existing search data first:
        ScrollView searchResultsScroller = (ScrollView) findViewById(R.id.searchResultsScrollView);
        if(searchResultsScroller.getChildCount() != 0) {
            searchResultsScroller.removeViewAt(0);
        }
        LinearLayout searchResultsContainer = new LinearLayout(getApplicationContext());
        searchResultsContainer.setOrientation(LinearLayout.VERTICAL);
        searchResultsScroller.addView(searchResultsContainer);

        boolean selectedBytes = ((RadioButton) findViewById(R.id.radio_search_bytes)).isChecked();
        String searchString = ((TextView) findViewById(R.id.userInputSearchData)).getText().toString();
        if(searchString.equals(""))
            return;
        else if(selectedBytes && !Utils.stringIsHexadecimal(searchString)) {
            searchResultsContainer.addView(LogEntryMetadataRecord.createDefaultEventRecord("ERROR", "Not a hexadecimal string.").getLayoutContainer());
            return;
        }
        else if(selectedBytes) {
            searchString = searchString.replace("[\n\t\r]+", "").replaceAll("..(?!$)", "$0 ");
        }

        boolean searchStatus = ((CheckBox) findViewById(R.id.entrySearchIncludeStatus)).isChecked();
        boolean searchAPDU = ((CheckBox) findViewById(R.id.entrySearchAPDU)).isChecked();
        boolean searchLogPayload = ((CheckBox) findViewById(R.id.entrySearchRawLogData)).isChecked();
        boolean searchLogHeaders = ((CheckBox) findViewById(R.id.entrySearchLogHeaders)).isChecked();
        int matchCount = 0;
        Log.i(TAG, "Searching for: " + searchString);
        for(int vi = 0; vi < logDataEntries.size(); vi++) {
            if (logDataEntries.get(vi) instanceof LogEntryMetadataRecord) {
                if (searchStatus && logDataEntries.get(vi).toString().contains(searchString)) {
                    searchResultsContainer.addView(logDataEntries.get(vi).cloneLayoutContainer());
                    matchCount++;
                }
                continue;
            }
            Log.i(TAG, ((LogEntryUI) logDataEntries.get(vi)).getPayloadDataString(selectedBytes));
            if (searchAPDU && ((LogEntryUI) logDataEntries.get(vi)).getAPDUString().contains(searchString) ||
                    searchLogHeaders && ((LogEntryUI) logDataEntries.get(vi)).getLogCodeName().contains(searchString) ||
                    searchLogPayload && ((LogEntryUI) logDataEntries.get(vi)).getPayloadDataString(selectedBytes).contains(searchString)) {
                LinearLayout searchResult = (LinearLayout) logDataEntries.get(vi).cloneLayoutContainer();
                searchResult.setVisibility(LinearLayout.VISIBLE);
                searchResult.setEnabled(true);
                searchResult.setMinimumWidth(350);
                searchResult.setMinimumHeight(150);
                LinearLayout.LayoutParams lllp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                searchResultsContainer.addView(searchResult, lllp);
                Log.i(TAG, "Case II: Record " + vi + " matches");
                matchCount++;
            }
        }
        double diffSeconds = (double) (System.currentTimeMillis() - startTime) / 1000.0;
        String resultStr = String.format(Locale.ENGLISH, "Explored #%d logs in %4g seconds for a total of #%d matching records.",
                logDataEntries.size(), diffSeconds, matchCount);
        searchResultsContainer.addView(LogEntryMetadataRecord.createDefaultEventRecord("SEARCH", resultStr).getLayoutContainer());
    }

}