package org.ale.scan2zotero.data;

import java.util.HashMap;
import java.util.Set;
import java.util.Map.Entry;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

public class Access implements Parcelable {
    public static final String TBL_NAME = "access";

    public static final String COL_KEY = "keyid";
    public static final String COL_GROUP = "groupid";
    public static final String COL_PERMISSION = "permission";

    public static final int NONE = 0;
    public static final int NOTE = 1;
    public static final int WRITE = 2;
    public static final int READ = 4;
    
    public int mKeyDbId;
    public final int[] mGroups;
    public final int[] mPerms;
    public final HashMap<Integer, Integer> mPermMap;
    
    public Access(int key, int[] groups, int[] permissions){
        mKeyDbId = key;
        mGroups = groups;
        mPerms = permissions;
        mPermMap = new HashMap<Integer, Integer>();
        for(int i = 0; i < groups.length; i++){
            mPermMap.put(groups[i], permissions[i]);
            Log.d("I'm an access!", groups[i] + " : " + permissions[i]);
        }
    }
    
    public Access(int[] groups, int[] permissions){
        this(Account.NOT_IN_DATABASE, groups, permissions);
    }

    public boolean canWriteLibrary() {
        if(mPermMap.containsKey(Group.GROUP_LIBRARY)){
            int libraryPerms = mPermMap.get(Group.GROUP_LIBRARY);
            return (libraryPerms & WRITE) == WRITE;
        }
        return false;
    }
    
    public boolean canWrite() {
        Set<Entry<Integer, Integer>> entries = mPermMap.entrySet();
        for(Entry<Integer,Integer> e : entries){
            if((e.getValue() & WRITE) == WRITE)
                return true;
        }
        return false;
    }

    public static final Creator<Access> CREATOR = new Creator<Access>() {
        public Access createFromParcel(Parcel in) {
            int key;
            int numEntries;

            key = in.readInt();
            numEntries = in.readInt();

            int[] groups = new int[numEntries];
            int[] perms = new int[numEntries];
            in.readIntArray(groups);
            in.readIntArray(perms);

            return new Access(key, groups, perms);
        }

        public Access[] newArray(int size) {
            return new Access[size];
        }
    };

    public static Access fromCursor(Cursor c, int keyid){
        int count = c.getCount();
        int[] groups = new int[count];
        int[] perms = new int[count];

        int i = 0;
        c.moveToFirst();
        while(!c.isAfterLast()){
            groups[i] = c.getInt(0);
            perms[i] = c.getInt(1);
            c.moveToNext();
            i++;
        }
        return new Access(keyid, groups, perms);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mKeyDbId);
        out.writeInt(mGroups.length);
        out.writeIntArray(mGroups);
        out.writeIntArray(mPerms);
    }
    
    public void writeToDB(ContentResolver cr) {
        if(mKeyDbId == Account.NOT_IN_DATABASE){
            Cursor keyCur = cr.query(S2ZDatabase.ACCOUNT_URI,
                    new String[]{Account._ID},
                    Account.COL_KEY+"=?",
                    new String[]{String.valueOf(mKeyDbId)},
                    null);
    
            if(keyCur.getCount() == 0) return; // Not in database
            keyCur.moveToFirst();
            mKeyDbId = keyCur.getInt(0);
            keyCur.close();
        }

        ContentValues[] values = new ContentValues[mGroups.length];
        for(int i=0; i<mGroups.length; i++){
            values[i] = new ContentValues();
            values[i].put(COL_KEY, mKeyDbId);
            values[i].put(COL_GROUP, mGroups[i]);
            values[i].put(COL_PERMISSION, mPerms[i]);
        }
        cr.bulkInsert(S2ZDatabase.ACCESS_URI, values);
    }
}