package org.ale.scan2zotero.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Random;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;

import org.ale.scan2zotero.data.Access;
import org.ale.scan2zotero.data.Account;
import org.ale.scan2zotero.data.Group;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import android.util.Log;

public class ZoteroAPIClient {
    private static final boolean DEBUG = false;
    
    private static final int R_ADD_ITEMS = 0;
    private static final int R_PERMISSIONS = 1;
    private static final int R_GROUPS = 2;
    private static final int R_NEW_COLLECTION = 3;

    //private static final String CLASS_TAG = ZoteroAPIClient.class.getCanonicalName();

    private static final String ZOTERO_BASE_URL = DEBUG ? "http://10.13.37.64" : "https://api.zotero.org";
    private static final String ZOTERO_USERS_URL = ZOTERO_BASE_URL + "/users";
    private static final String ZOTERO_GROUPS_URL = ZOTERO_BASE_URL + "/groups";

    private static final String HDR_WRITE_TOKEN = "X-Zotero-Write-Token";
    
    private Account mAccount;

    private DefaultHttpClient mHttpsClient;

    private RequestQueue mRequestQueue;

    private ZoteroHandler mHandler;

    public ZoteroAPIClient() {
        mHandler = ZoteroHandler.getInstance();
        mHttpsClient = HttpsClient.getInstance();
        mRequestQueue = RequestQueue.getInstance();
    }

    public void setAccount(Account acct){
        mAccount = acct;
    }

    public void addItems(JSONObject items) {
        APIRequest r = new APIRequest(mHandler, mHttpsClient);
        r.setRequestType(APIRequest.POST);
        r.setURI(buildURI(mAccount.getUid(), "items"));
        r.setContent(items.toString(), "application/json");
        r.addHeader(HDR_WRITE_TOKEN, newWriteToken());
        r.setReturnIdentifier("addItems");

        mRequestQueue.enqueue(r);
    }

    public void getPermissions() {
        APIRequest r = new APIRequest(mHandler, mHttpsClient);
        r.setRequestType(APIRequest.GET);
        r.setURI(buildURI(mAccount.getUid(), "keys", mAccount.getKey()));
        r.setReturnIdentifier("permissions");

        mRequestQueue.enqueue(r);
    }
    
    public void getUsersGroups() {
        APIRequest r = new APIRequest(mHandler, mHttpsClient);
        r.setRequestType(APIRequest.GET);
        r.setURI(buildURI(mAccount.getUid(), "groups"));
        r.setReturnIdentifier("groups");

        mRequestQueue.enqueue(r);
    }

    public void newCollection(String name, String parent){
        JSONObject collection = new JSONObject();

        try {
            collection.put("name", name);
            collection.put("parent", parent);
            APIRequest r = new APIRequest(mHandler, mHttpsClient);
            r.setRequestType(APIRequest.POST);
            r.setURI(buildURI(mAccount.getUid(), "collections"));
            r.setContent(collection.toString(), "application/json");
            r.addHeader(HDR_WRITE_TOKEN, newWriteToken());
            r.setReturnIdentifier("newCollection");

            mRequestQueue.enqueue(r);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public URI buildURI(String persona, String action) {
        // Returns:
        // https://api.zotero.org/<user or group>/<persona>/<action>?key=<key>
        String base;
        String query = "?key=" + mAccount.getKey();
        if(persona.equals(mAccount.getUid())){
            base = ZOTERO_USERS_URL;
        }else{
            base = ZOTERO_GROUPS_URL;
        }
        return URI.create(base+"/"+persona+"/"+action+query);
    }

    public URI buildURI(String persona, String action, String selection) {
        // Returns:
        // https://api.zotero.org/<user or group>/<persona>/<action>/<selection>?key=<key>
        String base;
        String query = "?key=" + mAccount.getKey();
        if(persona.equals(mAccount.getUid())){
            base = ZOTERO_USERS_URL;
        }else{
            base = ZOTERO_GROUPS_URL;
        }
        return URI.create(base+"/"+persona+"/"+action+"/"+selection+query);
    }

    public static String newWriteToken(){
        // Make 16 hex character write token
        Random rng = new Random();
        return Integer.toHexString(rng.nextInt()) + Integer.toHexString(rng.nextInt());
    }

    public static Access parsePermissions(String resp) {
        /* example:
          <key key="xxx">
          <access library="1" files="1" notes="1" write="1"/>
          <access group="12345" write="1"/>
          </key>
         */
        Log.d("APIClient", resp);
        DocumentBuilder builder = null;
        Document doc = null;

        try {
            builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            ByteArrayInputStream encXML = new ByteArrayInputStream(resp.getBytes("UTF8"));
            doc = builder.parse(encXML);
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(doc == null) return null;

        NodeList keys = doc.getElementsByTagName("key");
        if(keys.getLength() == 0) return null;

        Node key = keys.item(0);
        Node keyIdNode = key.getAttributes().getNamedItem("key");
        if(keyIdNode == null) return null;

        String keyId = keyIdNode.getNodeValue();
        Log.d("ZPIClient", keyId);

        NodeList accessTags = doc.getElementsByTagName("access");
        int[] groups = new int[accessTags.getLength()];
        int[] permissions = new int[accessTags.getLength()];
        for(int i=0; i<accessTags.getLength(); i++){
            permissions[i] = Access.READ;
            
            NamedNodeMap attr = accessTags.item(i).getAttributes();
            Node groupNode = attr.getNamedItem("group");
            if(groupNode == null){ // Library access?
                groupNode = attr.getNamedItem("library");
                if(groupNode == null) {
                    groups[i] = Group.NO_GROUP;
                    continue;
                }
                groups[i] = Group.GROUP_LIBRARY;
            }else{ // Individual group or all groups
                if(groupNode.getNodeValue().equals("all"))
                    groups[i] = Group.GROUP_ALL;
                else
                    groups[i] = Integer.parseInt(groupNode.getNodeValue());
            }

            Node writeNode = attr.getNamedItem("write");
            if(writeNode != null && writeNode.getNodeValue().equals("1")){
                permissions[i] |= Access.WRITE;
            }

            Node noteNode = attr.getNamedItem("notes");
            if(noteNode != null && noteNode.getNodeValue().equals("1")){
                permissions[i] |= Access.NOTE;
            }
        }
        return new Access(groups, permissions);
    }    
}
