
/*
 * Copyright (c) 2012-present Christopher J. Brody (aka Chris Brody)
 * Copyright (c) 2005-2010, Nitobi Software Inc.
 * Copyright (c) 2010, IBM Corporation
 */

package com.notify.node_sqlite3;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.lang.Number;

import java.sql.SQLException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * Android SQLite-Connector Database helper class
 */
class SQLiteConnectorDatabase extends SQLiteAndroidDatabase {

    SQLiteDatabase mydb;


    /**
     * NOTE: Using default constructor, no explicit constructor.
     */


    /**
     * Ignore Android bug workaround for NDK version
     */
    @Override
    public void bugWorkaround() {
    }

    /**
     * Executes a batch request and sends the results via cbc.
     *  @param queryarr   Array of query strings
     * @param jsonparams Array of JSON query parameters
     * @return
     */
    @Override
    public JSONArray executeSqlBatch(String[] queryarr, JSONArray[] jsonparams) {

        if (mydb == null) {
            // not allowed - can only happen if someone has closed (and possibly deleted) a database and then re-used the database
            Log.e(TAG, "database has been closed");
            return null;
        }

        int len = queryarr.length;
        JSONArray batchResults = new JSONArray();

        for (int i = 0; i < len; i++) {
            int rowsAffectedCompat = 0;
            boolean needRowsAffectedCompat = false;

            JSONObject queryResult = null;

            String errorMessage = "unknown";
            int sqliteErrorCode = -1;
            int code = 0; // SQLException.UNKNOWN_ERR

            try {
                String query = queryarr[i];

                queryResult = this.executeSQLiteStatement(query, jsonparams[i]);

            } catch (SQLException ex) {
                ex.printStackTrace();
                sqliteErrorCode = ex.getErrorCode();
                errorMessage = ex.getMessage();
                Log.v("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): SQL Error code = " + sqliteErrorCode + " message = " + errorMessage);

                //https://www.sqlite.org/rescode.html
                switch (sqliteErrorCode) {
                    case 1:
                        code = 5; // SQLException.SYNTAX_ERR
                        break;
                    case 13: // SQLITE_FULL
                        code = 4; // SQLException.QUOTA_ERR
                        break;
                    case 19:
                        code = 6; // SQLException.CONSTRAINT_ERR
                        break;
                    default:
                        /* do nothing */
                }
            } catch (JSONException ex) {
                // NOT expected:
                ex.printStackTrace();
                errorMessage = ex.getMessage();
                code = 0; // SQLException.UNKNOWN_ERR
                Log.e("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): UNEXPECTED JSON Error=" + errorMessage);
            }

            try {
                if (queryResult != null) {
                    JSONObject r = new JSONObject();

                    r.put("type", "success");
                    r.put("result", queryResult);

                    batchResults.put(r);
                } else {
                    JSONObject r = new JSONObject();
                    r.put("type", "error");

                    JSONObject er = new JSONObject();
                    er.put("message", errorMessage);
                    er.put("code", code);
                    r.put("result", er);

                    batchResults.put(r);
                }
            } catch (JSONException ex) {
                ex.printStackTrace();
                Log.e("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + ex.getMessage());
                // TODO what to do?
            }
        }

        Log.d( TAG, batchResults.toString());
        return batchResults;
    }

    /**
     * Get rows results from query cursor.
     * @param query
     * @param paramsAsJson
     * @return JSONObject rowsResult
     * @throws JSONException
     * @throws SQLException
     */
    private JSONObject executeSQLiteStatement(String query, JSONArray paramsAsJson) throws JSONException, SQLException {
        JSONObject rowsResult = new JSONObject();

        boolean hasRows = false;
        Cursor cursor = null;
//        SQLiteStatement myStatement = mydb.prepareStatement(query);

        // maybe wrap try with a transaction first...
        // https://developer.android.com/reference/kotlin/android/database/sqlite/SQLiteDatabase#begintransactionnonexclusive
        try {
            String[] params = null;

            params = new String[paramsAsJson.length()];

            cursor = mydb.rawQuery(query, params);
            cursor.moveToFirst();
            for (int i = 0; i < paramsAsJson.length(); ++i) {
                if (paramsAsJson.isNull(i)) {
                    cursor.isNull(i + 1);
                } else {
                    Object p = paramsAsJson.get(i);
                    if (p instanceof Float || p instanceof Double)
                        cursor.getDouble(i + 1);
//                    cursor.getDouble(i + 1, paramsAsJson.getDouble(i));

                    else if (p instanceof Number)
                        cursor.getLong(i + 1);
//                    cursor.getLong(i + 1, paramsAsJson.getLong(i));

                    else
//                        cursor.getString(i + 1, paramsAsJson.getString(i));
                        cursor.getString(i + 1);

                }
            }

            hasRows = cursor.moveToFirst();
        } catch (JSONException ex) {
            ex.printStackTrace();
            String errorMessage = ex.getMessage();
            Log.v("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + errorMessage);

            cursor.close();
            throw ex;
        }

        // If query result has rows
        if (hasRows) {
            JSONArray rowsArrayResult = new JSONArray();
            String key = "";
            int colCount = cursor.getColumnCount();
            int rowCount = cursor.getCount();
            // Build up JSON result object for each row
            do {
                JSONObject row = new JSONObject();
                try {
                    for (int i = 0; i < colCount; ++i) {
                        key = cursor.getColumnName(i);

                        switch (cursor.getType(i)) {
                            //https://developer.android.com/reference/android/database/Cursor#getType(int)
                            // FIELD_TYPE_NULL
                            case 0:
                                row.put(key, JSONObject.NULL);
                                break;
                            // FIELD_TYPE_FLOAT
                            case 2:
                                row.put(key, cursor.getDouble(i));
                                break;
                            // FIELD_TYPE_INTEGER
                            case 1:
                                row.put(key, cursor.getLong(i));
                                break;
                            // FIELD_TYPE_BLOB
                            case 4:
                            // FIELD_TYPE_STRING
                            case 3:
                            default: // (just in case)
                                row.put(key, cursor.getString(i));
                        }

                    }

                    rowsArrayResult.put(row);

                } catch (JSONException e) {
                    e.printStackTrace();
                }
                // unsure if the following properly implements `rowsAffected`
                if(cursor.isLast()) {
                    try {
                        long rowsAffected = cursor.getCount();
                        rowsResult.put("rowsAffected", rowsAffected);
                        if (rowsAffected > 0) {
                            long insertId = cursor.getPosition();
                            if (insertId > 0) {
                                rowsResult.put("insertId", insertId);
                            }
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            } while (cursor.moveToNext());
            try {
                rowsResult.put("rows", rowsArrayResult);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        cursor.close();

        return rowsResult;
    }

} /* vim: set expandtab : */

