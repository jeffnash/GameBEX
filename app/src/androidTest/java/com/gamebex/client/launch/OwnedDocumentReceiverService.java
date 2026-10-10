package com.gamebex.client.launch;

import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class OwnedDocumentReceiverService extends Service {
    @Override public IBinder onBind(Intent intent) {
        return new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                Bundle result = new Bundle();
                result.putInt("uid", Process.myUid());
                try {
                    Bundle request = message.getData();
                    Uri uri = Uri.parse(request.getString("uri"));
                    String operation = request.getString("operation");
                    result.putBoolean("document", DocumentsContract.isDocumentUri(OwnedDocumentReceiverService.this, uri));
                    result.putBoolean("tree", DocumentsContract.isTreeUri(uri));
                    if ("launch-observation".equals(operation)) {
                        android.content.SharedPreferences prefs = getSharedPreferences(OwnedFlycastTargetActivity.OBSERVATIONS, MODE_PRIVATE);
                        result.putInt("created", prefs.getInt("created", 0));
                        result.putLong("instance", prefs.getLong("instance", 0));
                        result.putLong("destroyed-instance", prefs.getLong("destroyed-instance", 0));
                        result.putString("delivered-uri", prefs.getString("delivered-uri", null));
                        result.putString("consumed-uri", prefs.getString("consumed-uri", null));
                        result.putString("launch-failure", prefs.getString("failure", null));
                        String bytes = prefs.getString("consumed-bytes", null);
                        if (bytes != null) result.putByteArray("consumed-bytes", android.util.Base64.decode(bytes, android.util.Base64.NO_WRAP));
                    } else if ("path".equals(operation)) {
                        result.putStringArrayList("path", new ArrayList<>(DocumentsContract.findDocumentPath(getContentResolver(), uri).getPath()));
                    } else if ("children".equals(operation)) {
                        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri));
                        ArrayList<String> names = new ArrayList<>();
                        try (Cursor cursor = getContentResolver().query(children, null, null, null, null)) {
                            while (cursor.moveToNext()) names.add(cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)));
                        }
                        result.putStringArrayList("names", names);
                    } else if ("write".equals(operation)) {
                        try (ParcelFileDescriptor file = getContentResolver().openFileDescriptor(uri, "rw")) {
                            result.putBoolean("writable", file != null);
                        }
                    } else if ("write-read-fd".equals(operation)) {
                        try (ParcelFileDescriptor file = getContentResolver().openFileDescriptor(uri, "r")) {
                            Os.write(file.getFileDescriptor(), new byte[] {100}, 0, 1);
                            result.putBoolean("writable", true);
                        }
                    } else if ("delete".equals(operation)) {
                        result.putBoolean("deleted", DocumentsContract.deleteDocument(getContentResolver(), uri));
                    } else if ("create".equals(operation)) {
                        result.putString("created", String.valueOf(DocumentsContract.createDocument(getContentResolver(), uri, "application/octet-stream", "owned-write-attempt.bin")));
                    } else if ("rename".equals(operation)) {
                        result.putString("renamed", String.valueOf(DocumentsContract.renameDocument(getContentResolver(), uri, "owned-rename-attempt.bin")));
                    } else {
                        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                            if (!cursor.moveToFirst()) throw new IllegalStateException("Missing document metadata");
                            result.putString("name", cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)));
                            result.putString("type", cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)));
                            result.putLong("size", cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)));
                            result.putLong("modified", cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)));
                            result.putInt("flags", cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)));
                        }
                        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(getContentResolver().openFileDescriptor(uri, "r"))) {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            byte[] buffer = new byte[1024];
                            int count;
                            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
                            result.putByteArray("bytes", bytes.toByteArray());
                        }
                    }
                    result.putBoolean("ok", true);
                } catch (Exception failure) {
                    result.putBoolean("ok", false);
                    result.putString("failure", failure.getClass().getName() + ": " + failure.getMessage());
                }
                Message reply = Message.obtain();
                reply.setData(result);
                try { message.replyTo.send(reply); } catch (RemoteException ignored) { }
            }
        }).getBinder();
    }
}
