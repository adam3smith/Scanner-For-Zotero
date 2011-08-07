package org.ale.scan2zotero.web;

import java.util.ArrayList;

import org.ale.scan2zotero.S2ZMainActivity;
import org.ale.scan2zotero.data.Access;
import org.ale.scan2zotero.web.APIRequest.APIResponse;
import org.apache.http.StatusLine;

import android.widget.Toast;

public class ZoteroHandler extends APIHandler {

    protected static ArrayList<Integer> mResponseTypes = new ArrayList<Integer>();
    protected static ArrayList<APIResponse> mResponses = new ArrayList<APIResponse>();
    
    private static ZoteroHandler mInstance = null;

    public static ZoteroHandler getInstance(){
        if(mInstance == null){
            mInstance = new ZoteroHandler();
        }
        return mInstance;
    }

    protected void dequeueMessages(){
        for(int i=0; i<mResponses.size(); i++){
            handleMessage(mResponseTypes.get(i).intValue(), mResponses.get(i));
        }
        mResponseTypes.clear();
        mResponses.clear();
    }

    // APIHandler.mActivity is guaranteed to be non-null
    // when the methods below are called
    protected void onStart(String id) {

    }

    protected void onProgress(String id, int percent) {

    }

    protected void onFailure(String id, StatusLine reason) {
        if(id.equals("permissions"))
            APIHandler.mActivity.postAccountPermissions(null);
        Toast.makeText(APIHandler.mActivity, id+" Failed", Toast.LENGTH_LONG).show();
    }

    protected void onException(String id, Exception exc) {
        if(id.equals("permissions"))
            APIHandler.mActivity.postAccountPermissions(null);
        exc.printStackTrace();
        Toast.makeText(APIHandler.mActivity, id+" Exception", Toast.LENGTH_LONG).show();
    }

    protected void onSuccess(final String id, String resp) {
        Toast.makeText(APIHandler.mActivity, id+" Success", Toast.LENGTH_LONG).show();
        if(id.equals("addItems")){
        }else if(id.equals("permissions")){
            handlePermissions(resp);
        }else if(id.equals("groups")){
        }else if(id.equals("newCollection")){
        }
    }

    private void handlePermissions(final String xml){
        new Thread(new Runnable(){
            @Override
            public void run() {
                final Access perms = ZoteroAPIClient.parsePermissions(xml);
                if(perms != null)
                    perms.writeToDB(APIHandler.mActivity.getContentResolver());
                APIHandler.mActivity.postAccountPermissions(perms);
            }
        }).start();
    }
}