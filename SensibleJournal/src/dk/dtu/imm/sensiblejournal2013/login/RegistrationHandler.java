package dk.dtu.imm.sensiblejournal2013.login;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.android.gms.gcm.GoogleCloudMessaging;
import dk.dtu.imm.sensiblejournal2013.R;

import dk.dtu.imm.sensiblejournal2013.MainActivity;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created with IntelliJ IDEA.
 * User: piotr
 * Date: 7/24/13
 * Time: 11:34 AM
 * To change this template use File | Settings | File Templates.
 */
public class RegistrationHandler extends Service {


    private static boolean started = false;
    private int mStartMode = Service.START_STICKY;
    private static final String SENDER_ID = "821664749841";
    private static final String TAG = "AUTH_RegistrationHandler";

    public static final String EXTRA_MESSAGE = "message";
    public static final String PROPERTY_REG_ID = "registration_id";
    private static final String PROPERTY_APP_VERSION = "appVersion";
    private static final String PROPERTY_ON_SERVER_EXPIRATION_TIME = "onServerExpirationTimeMs";

    private static final String SHOW_REGISTRATION_REMINDER = "dk.dtu.imm.sensiblejournal2013.show_registration_reminder";

    public static final String PROPERTY_SENSIBLE_TOKEN = "sensible_token";
    public static final String PROPERTY_SENSIBLE_REFRESH_TOKEN = "sensible_refresh_token";
    public static final String PROPERTY_SENSIBLE_TOKEN_TIMEOUT = "sensible_token_timeout";
    public static final String PROPERTY_SENSIBLE_CODE = "sensible_code";
    private static final int NOTIFICATION_ID = 1234;
    public static final long DAY = 24 * 60 * 60 * 1000;

    /**
     * Default lifespan (7 days) of a reservation until it is considered expired.
     */
    public static final long REGISTRATION_EXPIRY_TIME_MS = 7 * DAY;

    GoogleCloudMessaging gcm;
    AtomicInteger msgId = new AtomicInteger();
    Context context;

    String regid;

    Handler mHandler = new Handler();

    BroadcastReceiver br = null;
    PendingIntent pi;
    AlarmManager am;

    public enum SensibleRegistrationStatus {
        NOT_REGISTERED_NO_CODE, NOT_REGISTERED_HAS_CODE, REGISTERED_EXPIRED, REGISTERED
    }

	private static final String DOMAIN_URL = "https://www.sensible.dtu.dk/";
	private static final String CODE_TO_TOKEN_URL = DOMAIN_URL
			+ "sensible-dtu/authorization_manager/connector_raw/v1/auth/token_mobile/";
	private static final String REFRESH_TOKEN_URL = DOMAIN_URL
			+ "sensible-dtu/authorization_manager/connector_raw/v1/auth/refresh_token_mobile/";
	private static final String SET_GCM_ID_URL = DOMAIN_URL
			+ "sensible-dtu/authorization_manager/connector_raw/v1/auth/gcm/";
    		    
	private static final String SENSIBLE_ACCESS_TOKEN_KEY = "SENSIBLE_ACCESS_TOKEN_KEY";

    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started) {
            started = true;
            Log.d(TAG, "STARTED!");
            context = getApplicationContext();

            regid = getRegistrationId(context);
            Log.d(TAG, "RegId: " + regid);
            if (regid.length() == 0) {
                Log.d(TAG, "will register in the background");
                registerBackground();
            } else {
                Log.d(TAG, "Already registered at GCM");
                handleRegistration();
            }
        } else {
            Log.d(TAG, "already running, not starting");
        }
        return mStartMode;
    }

    private void handleRegistration() {

        SensibleRegistrationStatus status = getSensibleRegistrationStatus(context);
        Log.d(TAG, "Handling registration: " + status);
        switch (status) {
            case NOT_REGISTERED_NO_CODE:
                setupNotification();
                startAuthActivity();
                started = false;
                break;
            case NOT_REGISTERED_HAS_CODE:
                obtainToken();
                break;
            case REGISTERED_EXPIRED:
                refreshToken();
                break;
            case REGISTERED:
                cancelNotification();
                started = false;
                break;
        }
    }

    private void startAuthActivity() {
        Intent dialogIntent = new Intent(getBaseContext(), AuthActivity.class);
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getApplication().startActivity(dialogIntent);
    }
    private void setupNotification() {
        if (br == null) {
            br = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    sendNotification("Register your phone!",
                            "Touch to register your phone with SensibleDTU experiment");
                }
            };
            registerReceiver(br, new IntentFilter(SHOW_REGISTRATION_REMINDER) );
            pi = PendingIntent.getBroadcast(this, 0, new Intent(SHOW_REGISTRATION_REMINDER),
                    PendingIntent.FLAG_CANCEL_CURRENT);
            am = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        }
        sendNotification("Register your phone!",
                "Touch to register your phone with SensibleDTU experiment");
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis(),
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
    }

    @SuppressWarnings("deprecation")
	private void sendNotification(String title, String msg) {
        NotificationManager mNotificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                new Intent(context, AuthActivity.class), 0);

        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(context)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(msg);

        mBuilder.setContentIntent(contentIntent);
        mNotificationManager.notify(NOTIFICATION_ID, mBuilder.getNotification());
    }

    public static SensibleRegistrationStatus getSensibleRegistrationStatus(Context context) {
        long timeout = AuthActivity.getSystemPrefs(context).getLong(PROPERTY_SENSIBLE_TOKEN_TIMEOUT, 0l);
        if (timeout == 0l) {
            if (getSensibleCode(context).length() > 0) {
                return SensibleRegistrationStatus.NOT_REGISTERED_HAS_CODE;
            } else {
                return SensibleRegistrationStatus.NOT_REGISTERED_NO_CODE;
            }
        } else if (System.currentTimeMillis() + DAY > timeout) {
            Log.d(TAG, "Expiring in: " + (timeout - System.currentTimeMillis()));
            return SensibleRegistrationStatus.REGISTERED_EXPIRED;
        }
        else  {
            Log.d(TAG, "Expiring in: " + (timeout - System.currentTimeMillis()));
            return SensibleRegistrationStatus.REGISTERED;
        }
    }

    public static String getSensibleToken(Context context) {
        final SharedPreferences systemPrefs = AuthActivity.getSystemPrefs(context);
        return systemPrefs.getString(PROPERTY_SENSIBLE_TOKEN, "");
    }

    private String getSensibleRefreshToken() {
        final SharedPreferences systemPrefs = AuthActivity.getSystemPrefs(context);
        return systemPrefs.getString(PROPERTY_SENSIBLE_REFRESH_TOKEN, "");
    }

    private String getSensibleCode() {
        final SharedPreferences systemPrefs = AuthActivity.getSystemPrefs(context);
        Log.d(TAG, "Code from shared prefs: " + systemPrefs.getString(PROPERTY_SENSIBLE_CODE, ""));
        return systemPrefs.getString(PROPERTY_SENSIBLE_CODE, "");
    }

    private static String getSensibleCode(Context context) {
        return AuthActivity.getSystemPrefs(context).getString(PROPERTY_SENSIBLE_CODE, "");
    }


    public String getRegistrationId(Context context) {
        final SharedPreferences prefs = getGCMPreferences(context);
        String registrationId = prefs.getString(PROPERTY_REG_ID, "");
        if (registrationId.length() == 0) {
            Log.v(TAG, "Registration not found");
            return "";
        }

        int registeredVersion = prefs.getInt(PROPERTY_APP_VERSION, Integer.MIN_VALUE);
        int currentVersion = getAppVersion(context);
        if (registeredVersion != currentVersion || isRegistrationExpired(context)) {
            Log.v(TAG, "App version changed or registration expired");
            return "";
        }
        return registrationId;
    }

    private SharedPreferences getGCMPreferences(Context context) {
        return getSharedPreferences(AuthActivity.class.getSimpleName(), Context.MODE_PRIVATE);
    }

    private static int getAppVersion(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Could not get package name: " + e);
        }
    }

    private boolean isRegistrationExpired(Context context) {
        final SharedPreferences prefs = getGCMPreferences(context);
        // checks if the information is not stale
        long expirationTime =
                prefs.getLong(PROPERTY_ON_SERVER_EXPIRATION_TIME, -1);
        return System.currentTimeMillis() > expirationTime;
    }

    /**
     * Registers the application with GCM servers asynchronously.
     * <p>
     * Stores the registration id, app versionCode, and expiration time in the
     * application's shared preferences.
     */
    private void registerBackground() {
        new AsyncTask<Object, Object, Object>() {
            @Override
            protected Object doInBackground(Object... objects) {
                String msg = "";
                try {
                    Log.d(TAG, "doing it in the background...");
                    if (gcm == null) {
                        gcm = GoogleCloudMessaging.getInstance(context);
                    }
                    regid = gcm.register(SENDER_ID);
                    Log.d(TAG, "Device registered, registration id=" + regid);
                    msg = "Device registered, registration id=" + regid;

                    // You should send the registration ID to your server over HTTP,
                    // so it can use GCM/HTTP or CCS to send messages to your app.

                    // For this demo: we don't need to send it because the device
                    // will send upstream messages to a server that echo back the message
                    // using the 'from' address in the message.

                    // Save the regid - no need to register again.
                    setRegistrationId(context, regid);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            SensibleRegistrationStatus status = getSensibleRegistrationStatus(context);
                            if (status == SensibleRegistrationStatus.REGISTERED) {
                                setGcmId();
                            } else {
                                handleRegistration();
                            }
                        }
                    });
                    //mHandler.post(new ServerRegistrar(context, regid));
                } catch (IOException ex) {
                    msg = "Error :" + ex.getMessage();
                }
                return msg;
            }
        }.execute(null, null, null);
    }

    /**
     * Stores the registration id, app versionCode, and expiration time in the
     * application's {@code SharedPreferences}.
     *
     * @param context application's context.
     * @param regId registration id
     */
    private void setRegistrationId(Context context, String regId) {

        final SharedPreferences prefs = getGCMPreferences(context);
        int appVersion = getAppVersion(context);
        Log.v(TAG, "Saving regId on app version " + appVersion);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PROPERTY_REG_ID, regId);
        editor.putInt(PROPERTY_APP_VERSION, appVersion);
        long expirationTime = System.currentTimeMillis() + REGISTRATION_EXPIRY_TIME_MS;

        Log.v(TAG, "Setting registration expiry time to " +
                new Timestamp(expirationTime));
        editor.putLong(PROPERTY_ON_SERVER_EXPIRATION_TIME, expirationTime);
        editor.commit();
    }


    private void obtainToken() {
        Log.d(TAG, "obtaining Token");
        postData(CODE_TO_TOKEN_URL, getSensibleCode());
    }

    private void refreshToken() {
        Log.d(TAG, "refreshing Token");
        postData(REFRESH_TOKEN_URL, getSensibleRefreshToken());
    }

    private void setGcmId() {
        Log.d(TAG, "settings Gcm ID Token");
        postData(SET_GCM_ID_URL, getSensibleToken(context));
    }

    private void postData(String api_url, String extra_param) {
        new AsyncTask<String, Integer, Double>() {

            @Override
            protected Double doInBackground(String... strings) {
                //
                //HttpClient httpClient = new DefaultHttpClient();
                //SSLSocketFactory sf = (SSLSocketFactory)httpClient.getConnectionManager()
                //       .getSchemeRegistry().getScheme("https").getSocketFactory();
                //sf.setHostnameVerifier(new AllowAllHostnameVerifier());
                //

                HttpClient httpclient = new DefaultHttpClient();
                HttpPost httppost = null;
                httppost = new HttpPost(strings[0]);
                //?code=14e3b14e5759095cc7d74f169eac23&client_id=77a102b767a4b9ed0de963903aac32&client_secret=4190e63044c6b4687496261d44469d
                try {
                    ArrayList<BasicNameValuePair> nameValuePairs = new ArrayList<BasicNameValuePair>();
                    nameValuePairs.add(new BasicNameValuePair("client_id", ClientSecrets.CLIENT_ID));
                    nameValuePairs.add(new BasicNameValuePair("client_secret", ClientSecrets.CLIENT_SECRET));
                    TelephonyManager tm = (TelephonyManager)context.getSystemService(TELEPHONY_SERVICE);
                    String imei = tm.getDeviceId();
                    nameValuePairs.add(new BasicNameValuePair("device_id", imei));
                    Log.d(TAG, "Params: " + strings[0] + ", " + strings[1]);
                    if (strings[0].equals(CODE_TO_TOKEN_URL)) {
                        nameValuePairs.add(new BasicNameValuePair("code", strings[1]));
                    } else if (strings[0].equals(REFRESH_TOKEN_URL)) {
                        nameValuePairs.add(new BasicNameValuePair("refresh_token", strings[1]));
                    } else if (strings[0].equals(SET_GCM_ID_URL)) {
                        nameValuePairs.add(new BasicNameValuePair("gcm_id", getRegistrationId(context)));
                        nameValuePairs.add(new BasicNameValuePair("bearer_token", strings[1]));
                    }
                    for (BasicNameValuePair nvp : nameValuePairs) {
                        Log.d(TAG, nvp.getName() + " : " + nvp.getValue());
                    }

                    httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs));
                    Log.d(TAG, httppost.toString());
                    HttpResponse response = httpclient.execute(httppost);
                    String responseString = inputStreamToString(response.getEntity().getContent()).toString();
                    //String[] parts = responseString.split("<body>");
                    //Log.d(TAG, "Response: " + parts[1].replace(">",">\n"));


                    processResponse(strings[0], responseString);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (ClientProtocolException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (IOException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }
        }.execute(api_url, extra_param, null);

    }

    private void cancelNotification() {
        if (am != null) {
            am.cancel(pi);
        }
        NotificationManager mNotificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(NOTIFICATION_ID);
    }

    private void processResponse(String api_url, String response) {
        final SharedPreferences systemPrefs = AuthActivity.getSystemPrefs(context);
        try {
            JSONObject o = new JSONObject(response);
            if (o.has("error")) {
                Log.e(TAG, "Response contains error");
                if (response.contains("No such code")) {
                    Log.e(TAG, "Code not found, invalidate code");
                    SharedPreferences.Editor editor = systemPrefs.edit();
                    editor.putString(PROPERTY_SENSIBLE_CODE, "");
                    editor.commit();
                    handleRegistration();
                    return;
                }
            } else {
                String token = o.getString("access_token");
                long expiry = System.currentTimeMillis() + 7 * DAY;
                String refresh_token = o.getString("refresh_token");

                if (token != null) {
                    cancelNotification();
                    SharedPreferences.Editor editor = systemPrefs.edit();
                    editor.putString(PROPERTY_SENSIBLE_CODE, "");
                    editor.putString(PROPERTY_SENSIBLE_TOKEN, token);
                    editor.putString(PROPERTY_SENSIBLE_REFRESH_TOKEN, refresh_token);
                    editor.putLong(PROPERTY_SENSIBLE_TOKEN_TIMEOUT, expiry);
                    editor.commit();

                    SharedPreferences prefs = AuthActivity.getSystemPrefs(context);
                    prefs.edit();
                    editor.putString(SENSIBLE_ACCESS_TOKEN_KEY, token);
                    editor.commit();
                    
                    Intent intent = new Intent(getBaseContext(), MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        			startActivity(intent);
                    started = false;
                }
                return;
            }
        } catch (JSONException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }

    private StringBuilder inputStreamToString(InputStream is) {
        String line = "";
        StringBuilder total = new StringBuilder();

        // Wrap a BufferedReader around the InputStream
        BufferedReader rd = new BufferedReader(new InputStreamReader(is));

        // Read response until the end
        try {
            while ((line = rd.readLine()) != null) {
                Log.d(TAG, line);
                total.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        // Return full string
        return total;
    }

}
