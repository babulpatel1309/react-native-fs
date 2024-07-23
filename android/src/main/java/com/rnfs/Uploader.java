package com.rnfs;

import android.os.AsyncTask;
import android.webkit.MimeTypeMap;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.NoSuchKeyException;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Uploader extends AsyncTask<UploadParams, int[], UploadResult> {
    private UploadParams mParams;
    private UploadResult res;
    private AtomicBoolean mAbort = new AtomicBoolean(false);

    @Override
    protected UploadResult doInBackground(UploadParams... uploadParams) {
        mParams = uploadParams[0];
        res = new UploadResult();
        try {
            upload(mParams, res);
        } catch (Exception e) {
            res.exception = e;
        } finally {
            mParams.onUploadComplete.onUploadComplete(res);
        }
        return res;
    }

    private void upload(UploadParams params, UploadResult result) throws Exception {
        HttpURLConnection connection = null;
        DataOutputStream request = null;
        String crlf = "\r\n";
        String twoHyphens = "--";
        String boundary = "*****";
        String tail = crlf + twoHyphens + boundary + twoHyphens + crlf;
        BufferedInputStream responseStream = null;
        BufferedReader responseStreamReader = null;

        try {
            connection = (HttpURLConnection) params.src.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod(params.method);

            ReadableMapKeySetIterator headerIterator = params.headers.keySetIterator();
            while (headerIterator.hasNextKey()) {
                String key = headerIterator.nextKey();
                String value = params.headers.getString(key);
                connection.setRequestProperty(key, value);
            }

            if (!params.binaryStreamOnly) {
                connection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
            }

            StringBuilder metaDataBuilder = new StringBuilder();
            ReadableMapKeySetIterator fieldsIterator = params.fields.keySetIterator();
            while (fieldsIterator.hasNextKey()) {
                String key = fieldsIterator.nextKey();
                String value = params.fields.getString(key);
                metaDataBuilder.append(twoHyphens).append(boundary).append(crlf)
                        .append("Content-Disposition: form-data; name=\"").append(key).append("\"")
                        .append(crlf).append(crlf).append(value).append(crlf);
            }
            String metaData = metaDataBuilder.toString();

            StringBuilder stringDataBuilder = new StringBuilder();
            stringDataBuilder.append(metaData);
            String[] fileHeader = new String[params.files.size()];
            long totalFileLength = 0;

            for (int i = 0; i < params.files.size(); i++) {
                ReadableMap map = params.files.getMap(i);
                String name = map.getString("name");
                String filename = map.getString("filename");
                String filetype = map.hasKey("filetype") ? map.getString("filetype") : getMimeType(map.getString("filepath"));
                File file = new File(map.getString("filepath"));
                long fileLength = file.length();
                totalFileLength += fileLength;

                if (!params.binaryStreamOnly) {
                    String fileHeaderType = twoHyphens + boundary + crlf +
                            "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"" + crlf +
                            "Content-Type: " + filetype + crlf;
                    if (i == params.files.size() - 1) {
                        totalFileLength += tail.length();
                    }

                    String fileLengthHeader = "Content-length: " + fileLength + crlf;
                    fileHeader[i] = fileHeaderType + fileLengthHeader + crlf;
                    stringDataBuilder.append(fileHeaderType).append(fileLengthHeader).append(crlf);
                }
            }

            String stringData = stringDataBuilder.toString();
            long requestLength = totalFileLength + stringData.length() + params.files.size() * crlf.length();
            connection.setRequestProperty("Content-length", String.valueOf(requestLength));
            connection.setFixedLengthStreamingMode((int) requestLength);
            connection.connect();

            request = new DataOutputStream(connection.getOutputStream());
            WritableByteChannel requestChannel = Channels.newChannel(request);

            if (!params.binaryStreamOnly) {
                request.writeBytes(metaData);
            }

            int byteSentTotal = 0;
            for (int i = 0; i < params.files.size(); i++) {
                ReadableMap map = params.files.getMap(i);
                if (!params.binaryStreamOnly) {
                    request.writeBytes(fileHeader[i]);
                }

                File file = new File(map.getString("filepath"));
                long fileLength = file.length();
                long bufferSize = (long) Math.ceil(fileLength / 100.f);
                long bytesRead = 0;

                try (FileInputStream fileStream = new FileInputStream(file);
                     FileChannel fileChannel = fileStream.getChannel()) {

                    while (bytesRead < fileLength) {
                        long transferredBytes = fileChannel.transferTo(bytesRead, bufferSize, requestChannel);
                        bytesRead += transferredBytes;

                        if (mParams.onUploadProgress != null) {
                            byteSentTotal += transferredBytes;
                            mParams.onUploadProgress.onUploadProgress((int) totalFileLength, byteSentTotal);
                        }
                    }
                }

                if (!params.binaryStreamOnly) {
                    request.writeBytes(crlf);
                }
            }

            if (!params.binaryStreamOnly) {
                request.writeBytes(tail);
            }

            request.flush();

            responseStream = new BufferedInputStream(connection.getInputStream());
            responseStreamReader = new BufferedReader(new InputStreamReader(responseStream));
            WritableMap responseHeaders = Arguments.createMap();
            Map<String, List<String>> map = connection.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                responseHeaders.putString(entry.getKey(), entry.getValue().get(0));
            }

            StringBuilder responseBuilder = new StringBuilder();
            String line;
            while ((line = responseStreamReader.readLine()) != null) {
                responseBuilder.append(line).append("\n");
            }

            result.headers = responseHeaders;
            result.body = responseBuilder.toString();
            result.statusCode = connection.getResponseCode();

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (request != null) {
                request.close();
            }
            if (responseStream != null) {
                responseStream.close();
            }
            if (responseStreamReader != null) {
                responseStreamReader.close();
            }
        }
    }

    protected String getMimeType(String path) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(path);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        if (type == null) {
            type = "*/*";
        }
        return type;
    }

    protected void stop() {
        mAbort.set(true);
    }
}
