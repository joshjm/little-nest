package app.littlenest;

import org.json.JSONObject;
import java.util.List;

interface SyncTransport {
    void validateFolder(String folder) throws Exception;
    List<JSONObject> list(String folder) throws Exception;
    JSONObject download(JSONObject file) throws Exception;
    String upload(String folder, JSONObject operation) throws Exception;
}
