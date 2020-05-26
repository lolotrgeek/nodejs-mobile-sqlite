package com.notify.node_sqlite3;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class SQLite3Helper extends SQLiteOpenHelper {
    public static final String TAG = "SQLite3Helper";

    public static int DATABASE_VERSION = 1;
    public static String DATABASE_NAME = "";
    private static SQLite3Helper instance;

    public static SQLite3Helper getInstance() {
        return instance;
    }


    public SQLite3Helper(Context context, String filename, int version) {
        super(context, filename, null, version);
        DATABASE_NAME = filename;
        DATABASE_VERSION = version;
    }

    public void onCreate(SQLiteDatabase db) {
        instance = this;
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }

    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }
}