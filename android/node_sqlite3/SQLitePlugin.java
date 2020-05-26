package com.notify.node_sqlite3;

/*
 * Copyright (c) 2012-present Christopher J. Brody (aka Chris Brody)
 * Copyright (c) 2005-2010, Nitobi Software Inc.
 * Copyright (c) 2010, IBM Corporation
 */

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.File;

import java.lang.IllegalArgumentException;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SQLitePlugin extends Service {

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
    private Map<String, DBRunner> dbrmap = new ConcurrentHashMap<String, DBRunner>();

    protected ExecutorService threadPool = Executors.newCachedThreadPool();

    public static final String TAG = "SQLitePlugin";

    /**
     * NOTE: Using default constructor, no explicit constructor.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param actionAsString The action to execute.
     * @param args           JSONArry of arguments for the plugin.
     * @return Whether the action was valid.
     */
    public boolean execute(String actionAsString, JSONArray args) {

        Action action;
        try {
            action = Action.valueOf(actionAsString);
        } catch (IllegalArgumentException e) {
            // shouldn't ever happen
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }

        try {
            return executeAndPossiblyThrow(action, args);
        } catch (JSONException e) {
            // TODO: signal JSON problem to JS
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }
    }

    private boolean executeAndPossiblyThrow(Action action, JSONArray args)
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
                dbname = o.getString("name");
                // open database and start reading its queue
                this.startDatabase(dbname, o);
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
                    DBQuery q = new DBQuery(queries, jsonparams);
                    DBRunner r = dbrmap.get(dbname);
                    if (r != null) {
                        try {
                            r.q.put(q);
                        } catch (Exception e) {
                            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't add to queue", e);
                            Log.e(TAG, "INTERNAL PLUGIN ERROR: couldn't add to queue");
                        }
                    } else {
                        Log.e(TAG, "INTERNAL PLUGIN ERROR: database not open");
                    }
                }
                break;
        }

        return status;
    }

    /**
     * Clean up and close all open databases.
     */
    @Override
    public void onDestroy() {
        while (!dbrmap.isEmpty()) {
            String dbname = dbrmap.keySet().iterator().next();

            this.closeDatabaseNow(dbname);

            DBRunner r = dbrmap.get(dbname);
            try {
                // stop the db runner thread:
                r.q.put(new DBQuery());
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "INTERNAL PLUGIN CLEANUP ERROR: could not stop db thread due to exception", e);
            }
            dbrmap.remove(dbname);
        }
    }

    // --------------------------------------------------------------------------
    // LOCAL METHODS
    // --------------------------------------------------------------------------

    private void startDatabase(String dbname, JSONObject options) {
        DBRunner r = dbrmap.get(dbname);

        if (r != null) {
            // NO LONGER EXPECTED due to BUG 666 workaround solution:
            Log.e(TAG, "INTERNAL ERROR: database already open for db name: " + dbname);
        } else {
            r = new DBRunner(dbname, options);
            dbrmap.put(dbname, r);
            this.threadPool.execute(r);
        }
    }

    /**
     * Open a database.
     *
     * @param dbname The name of the database file
     */
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

            return mydb;
        } catch (Exception e) {
//            if (cbc != null) // XXX Android locking/closing BUG workaround
            Log.e(TAG, "can't open database " + e);
            throw e;
        }
    }

    /**
     * Close a database (in another thread).
     *
     * @param dbname The name of the database file
     */
    private void closeDatabase(String dbname) {
        DBRunner r = dbrmap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DBQuery(false));
            } catch (Exception e) {

                Log.e(TAG, "couldn't close database" + e);

                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
            }
        } else {

            Log.d(TAG, "database closed.");
        }
    }

    /**
     * Close a database (in the current thread).
     *
     * @param dbname The name of the database file
     */
    private void closeDatabaseNow(String dbname) {
        DBRunner r = dbrmap.get(dbname);

        if (r != null) {
            SQLiteAndroidDatabase mydb = r.mydb;

            if (mydb != null)
                mydb.closeDatabaseNow();
        }
    }

    public boolean deleteDatabase(String dbname) {
        DBRunner r = dbrmap.get(dbname);
        boolean deleteResult = false;
        if (r != null) {
            try {
                r.q.put(new DBQuery(true));
            } catch (Exception e) {

                Log.e(TAG, "couldn't close database" + e);

                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
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
            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't delete database", e);
            return false;
        }
    }

    private class DBRunner implements Runnable {
        final String dbname;
        private boolean oldImpl;
        private boolean bugWorkaround;

        final BlockingQueue<DBQuery> q;

        SQLiteAndroidDatabase mydb;

        DBRunner(final String dbname, JSONObject options) {
            this.dbname = dbname;
            this.oldImpl = options.has("androidOldDatabaseImplementation");
            Log.v(SQLitePlugin.class.getSimpleName(), "Android db implementation: built-in android.database.sqlite package");
            this.bugWorkaround = this.oldImpl && options.has("androidBugWorkaround");
            if (this.bugWorkaround)
                Log.v(SQLitePlugin.class.getSimpleName(), "Android db closing/locking workaround applied");

            this.q = new LinkedBlockingQueue<DBQuery>();
        }

        public void run() {
            try {
                this.mydb = openDatabase(dbname, this.oldImpl);
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error, stopping db thread", e);
                dbrmap.remove(dbname);
                return;
            }

            DBQuery dbq = null;

            try {
                dbq = q.take();

                while (!dbq.stop) {
                    mydb.executeSqlBatch(dbq.queries, dbq.jsonparams );

                    if (this.bugWorkaround && dbq.queries.length == 1 && dbq.queries[0] == "COMMIT")
                        mydb.bugWorkaround();

                    dbq = q.take();
                }
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
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
                            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't delete database", e);
                            Log.e(TAG, "couldn't delete database: " + e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);

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

        DBQuery(String[] myqueries, JSONArray[] params) {
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.queries = myqueries;
            this.jsonparams = params;
        }

        DBQuery(boolean delete) {
            this.stop = true;
            this.close = true;
            this.delete = delete;
            this.queries = null;
            this.jsonparams = null;
        }

        // signal the DBRunner thread to stop:
        DBQuery() {
            this.stop = true;
            this.close = false;
            this.delete = false;
            this.queries = null;
            this.jsonparams = null;
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

/* vim: set expandtab : */
