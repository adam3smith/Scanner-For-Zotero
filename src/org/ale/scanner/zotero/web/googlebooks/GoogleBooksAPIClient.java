/** 
 * Copyright 2011 John M. Schanck
 * 
 * ScannerForZotero is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ScannerForZotero is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ScannerForZotero.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.ale.scanner.zotero.web.googlebooks;

import java.net.URI;

import org.ale.scanner.zotero.Util;
import org.ale.scanner.zotero.data.CreatorType;
import org.ale.scanner.zotero.data.ItemField;
import org.ale.scanner.zotero.data.ItemType;
import org.ale.scanner.zotero.web.APIHandler;
import org.ale.scanner.zotero.web.APIRequest;
import org.ale.scanner.zotero.web.HttpsClient;
import org.ale.scanner.zotero.web.RequestQueue;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.Bundle;
import android.text.TextUtils;

public class GoogleBooksAPIClient {

    public static final String BOOK_SEARCH_ISBN = "https://www.googleapis.com/books/v1/volumes?prettyPrint=flase&q=isbn:";

    public static final String EXTRA_ISBN = "ISBN";

    private DefaultHttpClient mHttpsClient;

    private RequestQueue mRequestQueue;

    private APIHandler mHandler;
    
    public GoogleBooksAPIClient() {
        mHandler = GoogleBooksHandler.getInstance();
        mHttpsClient = HttpsClient.getInstance();
        mRequestQueue = RequestQueue.getInstance();
    }

    private APIRequest newRequest(){
        return new APIRequest(mHandler, mHttpsClient);
    }

    public void isbnLookup(String isbn) {
        APIRequest r = newRequest();
        r.setHttpMethod(APIRequest.GET);
        r.setURI(URI.create(BOOK_SEARCH_ISBN+isbn));
        Bundle extra = new Bundle();
        extra.putString(GoogleBooksAPIClient.EXTRA_ISBN, isbn);
        r.setExtra(extra);

        mRequestQueue.enqueue(r);
    }

    public static JSONObject translateJsonResponse(String isbn, String resp){
        // Returns empty JSONObject on failure.
        JSONObject translation = new JSONObject();
        JSONObject jsonResp = null;
        try {
            jsonResp = new JSONObject(resp);

            // Google search always returns "books#volumes"
            String kind = jsonResp.optString("kind");
            JSONArray respItems = jsonResp.optJSONArray("items");
            if(kind == null || !kind.equals("books#volumes") || respItems == null) {
                return null;
            }

            translation.put("items", new JSONArray());
            for(int i=0; i < respItems.length(); i++){
                // oItem is google's result, tItem is our translation of it
                JSONObject orig = respItems.getJSONObject(i);
                JSONObject volInfo = orig.optJSONObject("volumeInfo");
                if(volInfo == null){
                    continue;
                }
                JSONObject trans = new JSONObject();

                /* Set the itemType XXX: Always 'book' */
                trans.put(ItemType.type, ItemType.book);

                /* Get ISBN/ISSN info */
                String bestId = isbn;
                String bestType = ItemField.ISBN;
                JSONArray identifiers = volInfo.optJSONArray("industryIdentifiers");
                for(int j=0; identifiers != null && j<identifiers.length(); j++){
                    JSONObject identifier = identifiers.getJSONObject(j);
                    String idType = identifier.getString("type");

                    String id = identifier.getString("identifier");
                    if(bestId == null){
                        bestId = id;
                        if(idType.equals("ISSN")) bestType = ItemField.ISSN;
                    }
                    if(Util.isbnMatch(id, isbn)){
                        if(bestId != id && bestId.length() < id.length()){
                            bestId = id;
                            if(idType.equals("ISSN")) bestType = ItemField.ISSN;
                        }
                        break;
                    }
                }
                trans.put(bestType, bestId);

                /* Get title  */
                String subtitle = volInfo.optString("subtitle");
                if(!TextUtils.isEmpty(subtitle)){
                    trans.put(ItemField.title, 
                            volInfo.optString("title") + ": " + subtitle);
                }else{
                    trans.put(ItemField.title, volInfo.optString("title"));
                }

                /* Get Creators  */
                JSONArray creators = new JSONArray();
                JSONArray authors = volInfo.optJSONArray("authors");
                for(int j=0; authors != null && j<authors.length(); j++){
                    JSONObject author = new JSONObject();
                    author.put(CreatorType.type, CreatorType.Book.get(0));
                    author.put(ItemField.Creator.name, authors.get(j));
                    creators.put(author);
                }
                if(creators.length() > 0)
                    trans.put(ItemField.creators, creators);

                /* Get Other info  */
                trans.put(ItemField.publisher, volInfo.optString("publisher"))
                     .put(ItemField.date, volInfo.optString("publishedDate"))
                     .put(ItemField.numPages, volInfo.optString("pageCount"))
                     .put(ItemField.language, volInfo.optString("language"));

                translation.accumulate("items", trans);
            }
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
        return translation;
    }
}