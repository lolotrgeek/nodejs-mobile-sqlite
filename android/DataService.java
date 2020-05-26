package com.notify;

import android.app.PendingIntent;
import android.content.Intent;

import android.os.IBinder;

import android.app.Notification;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.os.Build;
import android.util.Log;

import com.notify.node_sqlite3.SQLiteAndroidDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class DataService extends NodeJS {
    private static String TAG = "DATA-SERVICE";

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
        System.loadLibrary("node");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public boolean _startedNodeAlready = false;

//    public Runnable request = new Runnable() {
//        @Override
//        public void run() {
//            int count = 0;
//            int maxTries = 10;
//            String nodeResponse = "";
//            while (true) {
//                try {
//                    URL localNodeServer = new URL("http://localhost:3000/");
//                    BufferedReader in = new BufferedReader(
//                            new InputStreamReader(localNodeServer.openStream()));
//                    String inputLine;
//                    while ((inputLine = in.readLine()) != null)
//                        nodeResponse = nodeResponse + inputLine;
//                    in.close();
//                    Log.i(TAG, nodeResponse);
//                    break;
//                } catch (Exception e) {
//                    if (++count == maxTries) {
//                        Log.e(TAG, e.getMessage());
//                    }
//                }
//            }
//        }
//    };


    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onCreate() {
        super.onCreate();
//        Thread dataThread = new Thread(request);
//        dataThread.setName("dataThread");
//        dataThread.start();
    }

//    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
//    @Override
//    public void onDestroy() {
//        super.onDestroy();
//        db.close();
//        Log.i(TAG, "Killing Listener...");
//    }

    public boolean isJSONValid(String test) {
        try {
            new JSONObject(test);
        } catch (JSONException ex) {
            // edited, to include @Arthur's comment
            // e.g. in case JSONArray is valid as well...
            try {
                new JSONArray(test);
            } catch (JSONException ex1) {
                return false;
            }
        }
        return true;
    }

    public JSONObject msgParse(String msg) {
        JSONObject obj = null;
        if (isJSONValid(msg)) {
            try {
                obj = new JSONObject(msg);
                Log.d(TAG, "Parsing Msg :" + obj.toString());
            } catch (JSONException e) {
                Log.e(TAG, e.getMessage());
            }
        }
        return obj;
    }

    public String eventParse(JSONObject obj) {
        String event = null;
        try {
            event = obj.get("event").toString();
            Log.d(TAG, "Parsing Event: " + event);

        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
        return event;
    }

    public JSONObject payloadParse(JSONObject obj) {
        JSONObject request = null;
        try {
            Log.d(TAG, "Parsing Payload...");
            JSONArray payload = new JSONArray(obj.get("payload").toString());
            request = new JSONObject(payload.get(0).toString());
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        }
        return request;
    }

    public JSONArray argsBuilder(JSONObject request) {
        JSONArray args = new JSONArray();
        JSONObject allargs = new JSONObject();
        JSONObject dbargs = new JSONObject();
        JSONObject txargs = new JSONObject();
        JSONArray executes = new JSONArray();
        try {
            String dbname = request.get("dbname").toString();
            JSONObject query = new JSONObject(request.get("query").toString());
            JSONArray params = new JSONArray(query.get("params").toString());
            String sql = query.get("sql").toString();
            txargs.put("sql", sql);
            txargs.put("params", params);
            executes.put(txargs);
            dbargs.put("dbname", dbname);
            allargs.put("dbargs", dbargs);
            allargs.put("executes", executes);
            args.put(allargs);
            Log.d(TAG, "Building args: " + args.toString());
            return args;
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
            return null;
        }
    }


    /**
     * Outgoing Messages from Android to Node
     *
     * @param response
     * @param event
     * @param err
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void handleOutgoingMessages(JSONObject response, String event, String err) {
        try {
            response.put("err", err);
            Log.i(TAG, event + "response :" + response.toString());
            super.sendMessageToNode(event, response.toString());
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * Outgoing messages from SqLite to Node
     *
     * @param event
     * @param err
     * @param batchResults
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void handleExecuteResponse(String event, String err, JSONArray batchResults) {
        JSONObject response = new JSONObject();
        try {
            JSONObject results = new JSONObject(batchResults.get(0).toString());
            JSONObject result = new JSONObject(results.get("result").toString());
            if (result.has("rows")) {
                JSONArray rows = new JSONArray();
                rows.put(result.get("rows").toString());
                response.put("rows", rows);
            } else {
                response.put("result", result);
            }
            response.put("err", err);
            Log.i(TAG + "_handleExecuteResponse", event + "response :" + response.toString());
            super.sendMessageToNode(event, response.toString());
        } catch (JSONException e) {
            Log.e(TAG + "_handleExecuteResponse", e.getMessage());
        }
    }

    /**
     * Incoming Messages from Node to Android
     *
     * @param msg
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void handleIncomingMessages(String msg) {
        try {
            JSONObject obj = msgParse(msg);
            String event = eventParse(obj);
            switch (event) {
                case "sqliteDatabase":
                    try {
                        JSONObject request = payloadParse(obj);
                        JSONArray args = new JSONArray();
                        Log.d(TAG, "Opening Database.");
                        args = args.put(request);
                        this.execute("open", args, event);
                    } catch (Exception e) {
                        Log.d(TAG, e.getMessage());
                    }
                    break;
                case "sqliteRun":
                case "sqliteAll":
                    try {
                        JSONObject request = payloadParse(obj);
                        JSONArray args = new JSONArray();
                        args = argsBuilder(request);
                        Log.d(TAG, "Running " + event + " Query - " + args.toString());
                        this.execute("backgroundExecuteSqlBatch", args, event);

                    } catch (Exception e) {
                        Log.d(TAG, e.getMessage());
                    }
                    break;
                default:
                    Log.d(TAG, "NonSQL Event:" + event);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not parse malformed JSON: \"" + msg + "\"");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void init() {
        super.startEngine("main.js");
        super.systemMessageToNode();

    }

    /**
     * Concurrent database runner map.
     * <p>
     * NOTE: no public static accessor to db (runner) map since it is not
     * expected to work properly with db threading.
     * <p>
     * FUTURE TBD put DBRunner into a public class that can provide external accessor.
     * <p>
     * ADDITIONAL NOTE: Storing as Map<String, DBRunner> to avoid portabiity issue
     * between Java 6/7/8 as discussed in:
     * https://gist.github.com/AlainODea/1375759b8720a3f9f094
     * <p>
     * THANKS to @NeoLSN (Jason Yang/楊朝傑) for giving the pointer in:
     * https://github.com/litehelpers/Cordova-sqlite-storage/issues/727
     */
    private Map<String, DataService.DBRunner> dbrmap = new ConcurrentHashMap<String, DataService.DBRunner>();

    protected ExecutorService threadPool = Executors.newCachedThreadPool();


    /**
     * NOTE: Using default constructor, no explicit constructor.
     */


    /**
     * Executes the request and returns PluginResult.
     *
     * @param actionAsString The action to execute.
     * @param args           JSONArray of arguments for the plugin.
     * @return Whether the action was valid.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean execute(String actionAsString, JSONArray args, String event) {
        DataService.Action action;
        try {
            action = DataService.Action.valueOf(actionAsString);
        } catch (IllegalArgumentException e) {
            // shouldn't ever happen
            Log.e(TAG + "_execute", e.getMessage());
            return false;
        }

        try {
            return executeAndPossiblyThrow(action, args, event);
        } catch (JSONException e) {
            // TODO: signal JSON problem to JS
            Log.e(TAG + "_execute_JSON", e.getMessage());
            return false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private boolean executeAndPossiblyThrow(DataService.Action action, JSONArray args, String event)
            throws JSONException {

        boolean status = true;
        JSONObject o;
        String echo_value;
        String dbname;

        switch (action) {
            case echoStringValue:
                o = args.getJSONObject(0);
                echo_value = o.getString("value");
                Log.d(TAG, echo_value);
                break;

            case open:
                o = args.getJSONObject(0);
                dbname = o.getString("dbname");
                // open database and start reading its queue
                this.startDatabase(dbname, o, event);
                break;

            case close:
                o = args.getJSONObject(0);
                dbname = o.getString("path");
                // put request in the q to close the db
                this.closeDatabase(dbname);
                break;

            case delete:
                o = args.getJSONObject(0);
                dbname = o.getString("path");

                deleteDatabase(dbname);

                break;

            case executeSqlBatch:
            case backgroundExecuteSqlBatch:
                JSONObject allargs = args.getJSONObject(0);
                JSONObject dbargs = allargs.getJSONObject("dbargs");
                dbname = dbargs.getString("dbname");
                JSONArray txargs = allargs.getJSONArray("executes");

                if (txargs.isNull(0)) {
                    Log.e(TAG, "INTERNAL PLUGIN ERROR: missing executes list");
                } else {
                    int len = txargs.length();
                    String[] queries = new String[len];
                    JSONArray[] jsonparams = new JSONArray[len];

                    for (int i = 0; i < len; i++) {
                        JSONObject a = txargs.getJSONObject(i);
                        queries[i] = a.getString("sql");
                        jsonparams[i] = a.getJSONArray("params");
                    }

                    // put db query in the queue to be executed in the db thread:
                    DataService.DBQuery q = new DataService.DBQuery(queries, jsonparams, event);
                    DataService.DBRunner r = dbrmap.get(dbname);
                    if (r != null) {
                        try {
                            r.q.put(q);
                        } catch (Exception e) {
                            Log.e(DataService.class.getSimpleName(), "couldn't add to queue", e);
                            handleOutgoingMessages(new JSONObject(), event, e.getMessage());
                        }
                    } else {
                        Log.e(TAG, "INTERNAL PLUGIN ERROR: database not open");
                        handleOutgoingMessages(new JSONObject(), event, "INTERNAL PLUGIN ERROR: database not open");
                    }
                }
                break;
        }
        return status;
    }

    /**
     * Clean up and close all open databases.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onDestroy() {
        super.onDestroy();
        while (!dbrmap.isEmpty()) {
            String dbname = dbrmap.keySet().iterator().next();

            this.closeDatabaseNow(dbname);

            DataService.DBRunner r = dbrmap.get(dbname);
            try {
                // stop the db runner thread:
                r.q.put(new DataService.DBQuery());
            } catch (Exception e) {
                Log.e(DataService.class.getSimpleName(), "INTERNAL PLUGIN CLEANUP ERROR: could not stop db thread due to exception", e);
            }
            dbrmap.remove(dbname);
        }
    }

    // --------------------------------------------------------------------------
    // LOCAL METHODS
    // --------------------------------------------------------------------------

    private void startDatabase(String dbname, JSONObject options, String event) {
        DataService.DBRunner r = dbrmap.get(dbname);

        if (r != null) {
            // NO LONGER EXPECTED due to BUG 666 workaround solution:
            Log.e(TAG, "INTERNAL ERROR: database already open for db name: " + dbname);
        } else {
            r = new DataService.DBRunner(dbname, options, event);
            dbrmap.put(dbname, r);
            this.threadPool.execute(r);
        }
    }

    /**
     * Open a database.
     *
     * @param dbname The name of the database file
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private SQLiteAndroidDatabase openDatabase(String dbname, boolean old_impl) throws Exception {
        try {
            // ASSUMPTION: no db (connection/handle) is already stored in the map
            // [should be true according to the code in DBRunner.run()]

            File dbfile = this.getDatabasePath(dbname);

            if (!dbfile.exists()) {
                dbfile.getParentFile().mkdirs();
            }

            Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

            // XXX TBD ???:
            // if (!old_impl) throw new UnsupportedOperationException("not implemented"); // XXX

            SQLiteAndroidDatabase mydb = new SQLiteAndroidDatabase();
            mydb.open(dbfile);

//            if (cbc != null) // XXX Android locking/closing BUG workaround
//                Log.d(TAG,);
            handleOutgoingMessages(new JSONObject(), "sqliteDatabase", null);
            return mydb;
        } catch (Exception e) {
//            if (cbc != null) // XXX Android locking/closing BUG workaround
            Log.e(TAG, "can't open database " + e);
            handleOutgoingMessages(new JSONObject(), "sqliteDatabase", e.getMessage());
            throw e;
        }
    }

    /**
     * Close a database (in another thread).
     *
     * @param dbname The name of the database file
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void closeDatabase(String dbname) {
        DataService.DBRunner r = dbrmap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DataService.DBQuery(false));
            } catch (Exception e) {

                Log.e(TAG, "couldn't close database" + e);
                handleOutgoingMessages(new JSONObject(), "sqliteDatabase", e.getMessage());
            }
        } else {
            handleOutgoingMessages(new JSONObject(), "sqliteDatabase", null);
            Log.d(TAG, "database closed.");
        }
    }

    /**
     * Close a database (in the current thread).
     *
     * @param dbname The name of the database file
     */
    private void closeDatabaseNow(String dbname) {
        DataService.DBRunner r = dbrmap.get(dbname);

        if (r != null) {
            SQLiteAndroidDatabase mydb = r.mydb;

            if (mydb != null)
                mydb.closeDatabaseNow();
        }
    }

    public boolean deleteDatabase(String dbname) {
        DataService.DBRunner r = dbrmap.get(dbname);
        boolean deleteResult = false;
        if (r != null) {
            try {
                r.q.put(new DataService.DBQuery(true));
            } catch (Exception e) {

                Log.e(TAG, "couldn't close database" + e);

                Log.e(DataService.class.getSimpleName(), "couldn't close database", e);
            }
        } else {
            deleteResult = this.deleteDatabaseNow(dbname);
            if (deleteResult) {
                Log.d(TAG, "database deleted.");
            } else {
                Log.e(TAG, "couldn't delete database");
            }

        }
        return deleteResult;
    }

    /**
     * Delete a database.
     *
     * @param dbname The name of the database file
     * @return true if successful or false if an exception was encountered
     */
    private boolean deleteDatabaseNow(String dbname) {
        File dbfile = this.getDatabasePath(dbname);

        try {
            return this.deleteDatabase(dbfile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(DataService.class.getSimpleName(), "couldn't delete database", e);
            return false;
        }
    }

    private class DBRunner implements Runnable {
        final String dbname;
        final String event;
        final String err;
        private boolean oldImpl;
        private boolean bugWorkaround;

        final BlockingQueue<DataService.DBQuery> q;

        SQLiteAndroidDatabase mydb;

        DBRunner(final String dbname, JSONObject options, String event) {
            this.dbname = dbname;
            this.event = event;
            this.err = null;
            this.oldImpl = options.has("androidOldDatabaseImplementation");
            Log.v(DataService.class.getSimpleName(), "Android db implementation: built-in android.database.sqlite package");
            this.bugWorkaround = this.oldImpl && options.has("androidBugWorkaround");
            if (this.bugWorkaround)
                Log.v(DataService.class.getSimpleName(), "Android db closing/locking workaround applied");

            this.q = new LinkedBlockingQueue<DataService.DBQuery>();
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public void run() {
            try {
                this.mydb = openDatabase(dbname, this.oldImpl);
            } catch (Exception e) {
                Log.e(DataService.class.getSimpleName(), "unexpected error, stopping db thread", e);
                dbrmap.remove(dbname);
                return;
            }

            DataService.DBQuery dbq = null;

            try {
                dbq = q.take();

                while (!dbq.stop) {
                    JSONArray batchResults = mydb.executeSqlBatch(dbq.queries, dbq.jsonparams);
                    handleExecuteResponse(event, err, batchResults);
                    if (this.bugWorkaround && dbq.queries.length == 1 && dbq.queries[0] == "COMMIT")
                        mydb.bugWorkaround();

                    dbq = q.take();

                }
            } catch (Exception e) {
                Log.e(DataService.class.getSimpleName(), "unexpected error", e);
            }

            if (dbq != null && dbq.close) {
                try {
                    closeDatabaseNow(dbname);

                    dbrmap.remove(dbname); // (should) remove ourself

                    if (!dbq.delete) {
                        Log.d(TAG, "attempting to delete database.");
                    } else {
                        try {
                            boolean deleteResult = deleteDatabaseNow(dbname);
                            if (deleteResult) {
                                Log.d(TAG, "database deleted");
                            } else {
                                Log.e(TAG, "couldn't delete database");
                            }
                        } catch (Exception e) {
                            Log.e(DataService.class.getSimpleName(), "couldn't delete database", e);
                            Log.e(TAG, "couldn't delete database: " + e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(DataService.class.getSimpleName(), "couldn't close database", e);

                    Log.e(TAG, "couldn't close database: " + e);

                }
            }
        }
    }

    private final class DBQuery {
        // XXX TODO replace with DBRunner action enum:
        final boolean stop;
        final boolean close;
        final boolean delete;
        final String[] queries;
        final JSONArray[] jsonparams;
        final String event;


        DBQuery(String[] myqueries, JSONArray[] params, String event) {
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.queries = myqueries;
            this.jsonparams = params;
            this.event = event;
        }

        DBQuery(boolean delete) {
            this.stop = true;
            this.close = true;
            this.delete = delete;
            this.queries = null;
            this.jsonparams = null;
            this.event = null;
        }

        // signal the DBRunner thread to stop:
        DBQuery() {
            this.stop = true;
            this.close = false;
            this.delete = false;
            this.queries = null;
            this.jsonparams = null;
            this.event = null;
        }
    }

    private static enum Action {
        echoStringValue,
        open,
        close,
        delete,
        executeSqlBatch,
        backgroundExecuteSqlBatch,
    }
}