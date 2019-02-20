package com.wtuadn.apkuploader;

import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;
import net.dongliu.apk.parser.bean.Icon;
import net.dongliu.apk.parser.bean.IconFace;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class ApkUploader {
    public static void main(String[] args) {
        final String apkDir = args[0];
        final String pgyerKey = args[1];
        final String firToken = args[2];

        LinkedList<File> list = new LinkedList<>();
        findApkFiles(list, new File(apkDir));
        for (int i = 0; i < list.size(); i++) {
            File file = list.get(i);
            System.out.println(i + 1 + " " + file);
        }

        Scanner sc = new Scanner(System.in);
        System.out.println();
        System.out.print("select a apk file: ");
        int index = sc.nextInt() - 1;
        File apkFile = list.get(index);

        System.out.println("1 pgyer");
        System.out.println("2 fir");
        System.out.print("select a platform: ");
        int platform = sc.nextInt();
        switch (platform) {
            case 1:
                uploadToPgyer(apkFile, pgyerKey);
                break;
            case 2:
                uploadToFir(apkFile, firToken);
        }
    }

    private static void findApkFiles(LinkedList<File> list, File file) {
        String fileName = file.getName();
        if (file.isDirectory()) {
            File[] listFiles = file.listFiles();
            for (int i = 0; i < listFiles.length; i++) {
                findApkFiles(list, listFiles[i]);
            }
        } else if (fileName.endsWith(".apk")) {
            list.add(file);
        }
    }

    private static void uploadToPgyer(final File apkFile, final String pgyerKey) {
        try {
            HashMap<String, String> params = new HashMap<>(1);
            params.put("_api_key", pgyerKey);
            String result = multipartRequest("https://www.pgyer.com/apiv2/app/upload", params, apkFile, null, null, "file");
            System.out.println(result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void uploadToFir(final File apkFile, final String firToken) {
        try {
            ApkFile apkFileInfo = new ApkFile(apkFile);
            ApkMeta apkMeta = apkFileInfo.getApkMeta();
            URL url = new URL("http://api.fir.im/apps");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("Content-Type", "application/json");

            OutputStream os = connection.getOutputStream();
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("type", "android");
            jsonObject.put("bundle_id", apkMeta.getPackageName());
            jsonObject.put("api_token", firToken);
            os.write(jsonObject.toString().getBytes("UTF-8"));
            os.flush();

            InputStream inputStream = connection.getInputStream();
            JSONObject cert = new JSONObject(convertStreamToString(inputStream)).getJSONObject("cert");
            inputStream.close();
            os.close();

            List<IconFace> allIcons = apkFileInfo.getAllIcons();
            Icon icon = null;
            for (int i = 0; i < allIcons.size(); ++i) {
                IconFace iconFace = allIcons.get(i);
                if (iconFace instanceof Icon) {
                    Icon newIcon = (Icon) iconFace;
                    if (icon != null && newIcon.getDensity() < icon.getDensity()) {
                        continue;
                    }
                    icon = newIcon;
                }
            }
            if (icon != null) {
                System.out.println("upload icon:");
                JSONObject iconCert = cert.getJSONObject("icon");
                HashMap<String, String> params = new HashMap<>(2);
                params.put("key", iconCert.getString("key"));
                params.put("token", iconCert.getString("token"));
                String[] split = icon.getPath().split("/");
                String result = multipartRequest(iconCert.getString("upload_url"), params, null, split[split.length - 1], icon.getData(), "file");
                System.out.println(result);
            }
            System.out.println("upload apk file:");
            JSONObject apkCert = cert.getJSONObject("binary");
            HashMap<String, String> params = new HashMap<>(5);
            params.put("key", apkCert.getString("key"));
            params.put("token", apkCert.getString("token"));
            params.put("x:name", apkMeta.getName());
            params.put("x:version", apkMeta.getVersionName());
            params.put("x:build", String.valueOf(apkMeta.getVersionCode()));
            String result = multipartRequest(apkCert.getString("upload_url"), params, apkFile, null, null, "file");
            System.out.println(result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public static String multipartRequest(String urlTo, Map<String, String> params, File file, String fileName, byte[] data, String fileParamKey) throws Exception {
        String twoHyphens = "--";
        String boundary = "*****" + Long.toString(System.currentTimeMillis()) + "*****";
        String lineEnd = "\r\n";

        String result = "";

        int bytesRead, bytesAvailable, bufferSize;
        byte[] buffer;
        int maxBufferSize = 1 * 1024 * 1024;

        try {
            URL url = new URL(urlTo);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(twoHyphens + boundary + lineEnd);
            if (fileName == null) fileName = file.getName();
            outputStream.writeBytes("Content-Disposition: form-data; name=\"" + fileParamKey + "\"; filename=\"" + fileName + "\"" + lineEnd);
            outputStream.writeBytes("Content-Transfer-Encoding: binary" + lineEnd);

            outputStream.writeBytes(lineEnd);

            if (data == null) {
                FileInputStream fileInputStream = new FileInputStream(file);
                bytesAvailable = fileInputStream.available();
                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                buffer = new byte[bufferSize];

                bytesRead = fileInputStream.read(buffer, 0, bufferSize);
                while (bytesRead > 0) {
                    outputStream.write(buffer, 0, bufferSize);
                    bytesAvailable = fileInputStream.available();
                    bufferSize = Math.min(bytesAvailable, maxBufferSize);
                    bytesRead = fileInputStream.read(buffer, 0, bufferSize);
                }
                fileInputStream.close();
            } else {
                outputStream.write(data);
            }

            outputStream.writeBytes(lineEnd);

            // Upload POST Data
            Iterator<String> keys = params.keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = params.get(key);

                outputStream.writeBytes(twoHyphens + boundary + lineEnd);
                outputStream.writeBytes("Content-Disposition: form-data; name=\"" + key + "\"" + lineEnd);
                outputStream.writeBytes("Content-Type: text/plain" + lineEnd);
                outputStream.writeBytes(lineEnd);
                outputStream.writeBytes(value);
                outputStream.writeBytes(lineEnd);
            }

            outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);


            if (200 != connection.getResponseCode()) {
                throw new Exception("Failed to upload code:" + connection.getResponseCode() + " " + connection.getResponseMessage());
            }

            InputStream inputStream = connection.getInputStream();

            result = convertStreamToString(inputStream);

            inputStream.close();
            outputStream.flush();
            outputStream.close();

            return result;
        } catch (Exception e) {
            throw new Exception(e);
        }
    }

    private static String convertStreamToString(InputStream is) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();

        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }
}