package com.notify.node_sqlite3;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.provider.BaseColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Array;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;


public class SQLite3Bindings extends SQLite3Helper {
        public static final String TAG = "SQLite3Bindings";

    /**
     * Thread pool for database operations
     */
    protected ExecutorService threadPool = Executors.newCachedThreadPool();

    /**
     * Multiple database runner map (static).
     * NOTE: no public static accessor to db (runner) map since it would not work with db threading.
     * FUTURE put DBRunner into a public class that can provide external accessor.
     */
//    static ConcurrentHashMap<String, DBRunner> dbrmap = new ConcurrentHashMap<String, DBRunner>();

        public SQLite3Bindings(Context context, String filename, int version) {
            super(context, filename, version);
        }

        SQLiteDatabase db = this.getWritableDatabase();
        /**
         * run a rawQuery with no expected return values
         * @param query
         * @param params
         * @return
         */
        public String run(String query, String[] params) {
            try {
                Cursor cursor = db.rawQuery(query, params);
                cursor.close();

                return "success";
            } catch (SQLiteException e) {
                Log.e(TAG, e.getMessage());
                return e.getMessage();
            }
        }

        /**
         * run a rawQuery, expecting to return a set of rows
         * @param query
         * @param params
         * @return
         */
        public JSONObject all(String query, String[] params) throws JSONException {
            JSONObject result = new JSONObject();
            try {
                JSONArray rows = new JSONArray();
                Cursor cursor = db.rawQuery(query, params);
                cursor.moveToFirst();
                String[] columns = cursor.getColumnNames();
                for (String column : columns) {
                    String row = cursor.getString(cursor.getColumnIndex(column));
                    rows.put(row);
                    cursor.moveToLast();
                }
                cursor.close();
                result.put("rows", rows.toString());
            } catch (SQLiteException | JSONException e) {
                String err = "err " + e.getMessage();
                Log.e(TAG, e.getMessage());
                result.put("err", e.getMessage());
            }
            return result;
        }

//    class DBRunner implements Runnable {
//        final String filename;
//        final int openFlags;
//        private String assetFilename;
//
//        public DBRunner(String filename)
//
//                throws Exception {
//
//        }
//
//        public void run() { // run the service
//            try {
//                for (;;) {
//
//                }
//            } catch (Exception ex) {
//                db.close();
//                threadPool.shutdown();
//            }
//        }
//    }
    }
