package com.acunetix;

import com.google.common.base.Charsets;
import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.file.Paths;
import java.util.*;


public class Engine {
    private String apiUrl;
    private String apiKey;
    private static final Map<String, String[]> threatCategory = new HashMap<>();

    static {
        threatCategory.put("High", new String[]{"3"});
        threatCategory.put("Medium", new String[]{"3", "2"});
        threatCategory.put("Low", new String[]{"3", "2", "1"});
    }

    public Engine(String apiUrl, String apiKey) {
//        System.setProperty("proxySet", "true");
//        System.getProperty("proxySet");

        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    public static String getThreatName(String threat) {
        switch (threat) {
            case "3":
                return "High";
            case "2":
                return "Medium";
            case "1":
                return "Low";
        }
        return null;
    }

    private static class Resp {
        int respCode;
        String respStr = null;
        JSONObject jso = null;
    }


    private HttpsURLConnection openConnection(String endpoint, String method) throws IOException {
        return openConnection(endpoint, method, "application/json; charset=UTF-8");
    }

    private HttpsURLConnection openConnection(String endpoint) throws IOException {
        return openConnection(endpoint, "GET", "application/json; charset=UTF-8");
    }

    private HttpsURLConnection openConnection(String endpoint, String method, String contentType) throws IOException {
        URL url = new URL(endpoint);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.addRequestProperty("X-AUTH", apiKey);
        return connection;
    }


    public Resp doGet(String urlStr) throws IOException {
        HttpsURLConnection connection = openConnection(urlStr);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
            String inputLine;
            StringBuilder resbuf = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                resbuf.append(inputLine);
            }

            Resp resp = new Resp();
            resp.respCode = connection.getResponseCode();
            resp.jso = JSONObject.fromObject(resbuf.toString());
            return resp;
        }
    }

    public String doDownload(String urlStr, String savePath, String buildNumber) throws IOException {
        HttpsURLConnection connection = openConnection(urlStr, "GET", "text/html; charset=UTF-8");

        try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
            // get the file name
            String cd = connection.getHeaderField("Content-Disposition");
            String fileName = null;
            if (cd != null && cd.contains("=")) {
                fileName = cd.split("=")[1].trim().replaceAll("\"", "");
            }
            String filePath = findAvailableFileName(savePath, buildNumber, fileName);
            String inputLine;
            try {
                try (FileOutputStream dfile = new FileOutputStream(filePath)) {
                    while ((inputLine = in.readLine()) != null) {
                        dfile.write(inputLine.getBytes(Charsets.UTF_8));
                    }
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            return fileName;
        }
    }

    public int doTestConnection(String urlStr) throws IOException {
        HttpsURLConnection connection = openConnection(urlStr);
        return connection.getResponseCode();
    }

    public Resp doPost(String urlStr) throws IOException {
        HttpsURLConnection connection = openConnection(urlStr,"POST");
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(true);
        Resp resp = new Resp();
        resp.respCode = connection.getResponseCode();
        return resp;
    }

    public Resp doPostLoc(String urlStr, String urlParams) throws IOException {
        HttpsURLConnection connection = openConnection(urlStr, "POST");
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(true);

        try (DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream())) {
            outputStream.writeBytes(urlParams);
        }

        String location = connection.getHeaderField("Location");
        Resp resp = new Resp();
        resp.respCode = connection.getResponseCode();
        resp.respStr = location.substring(location.lastIndexOf("/") + 1);
        return resp;
    }

    public JSONArray getTargets() throws IOException {
        Resp resp = doGet(apiUrl + "/targets");
        if (resp.respCode == 200) {
            return resp.jso.getJSONArray("targets");
        }
        throw new IOException(SR.getString("bad.response.0", resp.respCode));
    }

    public String getTargetName(String targetId) throws IOException {
        JSONObject jso = doGet(apiUrl + "/targets").jso;
        JSONArray targets = jso.getJSONArray("targets");
        for (int i = 0; i < targets.size(); i++) {
            JSONObject item = targets.getJSONObject(i);
            String target_id = item.getString("target_id");
            if (target_id.equals(targetId)) {
                String address = item.getString("address");
                String description = item.getString("description");
                String target_name = address;
                if (description.length() > 0) {
                    if (description.length() > 100) {
                        description = description.substring(0, 100);
                    }
                    target_name += "  (" + description + ")";
                }
                return target_name;
            }
        }
        return null;
    }

    public JSONArray getScanningProfiles() throws IOException {
        Resp resp = doGet(apiUrl + "/scanning_profiles");
        if (resp.respCode == 200) {
            return resp.jso.getJSONArray("scanning_profiles");
        }
        throw new IOException(SR.getString("bad.response.0", resp.respCode));
    }

    public Boolean checkScanExist(String scanId) {
        try {
            JSONArray scans = getScans();
            for (int i = 0; i < scans.size(); i++) {
                JSONObject item = scans.getJSONObject(i);
                String id = item.getString("scan_id");
                if (id.equals(scanId)) {
                    return true;
                }
            }
        }
        catch (IOException e){
            e.printStackTrace();
        }
        return false;
    }

    public String startScan(String scanningProfileId, String targetId, Boolean waitFinish) throws IOException {
        JSONObject jso = new JSONObject();
        jso.put("target_id", targetId);
        jso.put("profile_id", scanningProfileId);
        JSONObject jsoChild = new JSONObject();
        jsoChild.put("disable", false);
        jsoChild.put("start_date", JSONNull.getInstance());
        jsoChild.put("time_sensitive", false);
        jso.put("schedule", jsoChild);
        String scanId = doPostLoc(apiUrl + "/scans", jso.toString()).respStr;
        if (waitFinish) {
            while (!getScanStatus(scanId).equals("completed")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        return scanId;
    }


    private JSONArray getScans() throws IOException {
        Resp resp = doGet(apiUrl + "/scans");
        if (resp.respCode == 200) {
            return resp.jso.getJSONArray("scans");
        }
        throw new IOException(SR.getString("bad.response.0", resp.respCode));
    }

    public String getScanThreat(String scanId) throws IOException {
        JSONObject jso = doGet(apiUrl + "/scans/" + scanId).jso;
        return jso.getJSONObject("current_session").getString("threat");
    }


    public String getScanStatus(String scanId) throws IOException {
        JSONObject jso = doGet(apiUrl + "/scans/" + scanId).jso;
        return jso.getJSONObject("current_session").getString("status");
    }

    public void stopScan(String scanId) {
        try {
            Resp resp = doPost(apiUrl + "/scans/" + scanId + "/abort");
            if (resp.respCode != 204) {
                throw new IOException(SR.getString("bad.response.0", resp.respCode));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public JSONArray getReportTemplates() throws IOException {
        Resp resp = doGet(apiUrl + "/report_templates");
        if (resp.respCode == 200) {
            return resp.jso.getJSONArray("templates");
        }
        throw new IOException(SR.getString("bad.response.0", resp.respCode));
    }

    public String getReportTemplateName(String reportTemplateId) throws IOException {
        Resp resp = doGet(apiUrl + "/report_templates");
        if (resp.respCode == 200) {
            JSONArray jsa = resp.jso.getJSONArray("templates");
            for (int i = 0; i < jsa.size(); i++) {
                JSONObject item = jsa.getJSONObject(i);
                if (item.getString("template_id").equals(reportTemplateId)) {
                    return item.getString("name");
                }
            }
            return null;
        }
        throw new IOException(SR.getString("bad.response.0", resp.respCode));
    }

    private String getReportStatus(String reportId) throws IOException {
        JSONObject jso = doGet(apiUrl + "/reports/" + reportId).jso;
        return jso.getString("status");
    }

    public void waitReportStatus(String reportId) throws IOException, InterruptedException {
        while (!getReportStatus(reportId).equals("completed")) {
            Thread.sleep(1000);
        }
    }

    public String generateReport(String sourceId, String reportTemplateId, String listType) throws IOException, InterruptedException {
        //returns download link of html report
        JSONObject jso = new JSONObject();
        jso.put("template_id", reportTemplateId);
        JSONObject jsoChild = new JSONObject();
        jsoChild.put("list_type", listType);
        List<String> id_list = new ArrayList<>();
        id_list.add(sourceId);
        jsoChild.put("id_list", id_list);
        jso.put("source", jsoChild);
        String reportId = doPostLoc(apiUrl + "/reports", jso.toString()).respStr;
        waitReportStatus(reportId);
        String[] downloadLinkList = doGet(apiUrl + "/reports/" + reportId).jso.getString("download").split(",");
        String downloadLink = null;
        for (String item : downloadLinkList) {
            if (item.contains(".html")) {
                downloadLink = item.replaceAll("\"", "").replaceAll("\\[", "".replaceAll("]", ""));
                break;
            }
        }
        // download report
        return downloadLink;
    }

    public Boolean checkThreat(String checkThreat, String scanThreat) {
        //return true if the threat detected is equal or greater than threat set
        //checkthreat is the level set in plugin config and scanThreat from the scan result
        if (checkThreat.equals("DoNotFail")) {
            return false;
        }
        return Arrays.asList(threatCategory.get(checkThreat)).contains(scanThreat);
    }

    public String findAvailableFileName(String savePath, String buildNumber, String reportName) {
        int i = 1;
        while (true) {
            String fileName = Paths.get(savePath, buildNumber + "_" + i + "_" + reportName).toString();
            File f = new File(fileName);
            if (f.exists()) {
                i++;
            } else {
                return fileName;
            }
        }
    }

}

class ConnectionException extends RuntimeException {
    public ConnectionException() {
        super(SR.getString("cannot.connect.to.application"));
    }
    public ConnectionException(String message) {
        super(message);
    }
}