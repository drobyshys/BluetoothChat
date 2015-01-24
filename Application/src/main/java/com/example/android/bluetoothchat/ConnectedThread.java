package com.example.android.bluetoothchat;

import android.bluetooth.BluetoothSocket;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * This thread runs during a connection with a remote device.
 * It handles all incoming and outgoing transmissions.
 */
class ConnectedThread extends Thread {

    private static final String TAG = "ConnectedThread";
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final BluetoothChatService service;

    public ConnectedThread(BluetoothChatService service, BluetoothSocket socket) {
        this.service = service;
        Log.d(TAG, "create ConnectedThread");
        mmSocket = socket;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        // Get the BluetoothSocket input and output streams
        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            Log.e(TAG, "temp sockets not created", e);
        }

        mmInStream = tmpIn;
        mmOutStream = tmpOut;
    }

    public void run() {
        Log.i(TAG, "BEGIN mConnectedThread");
        byte[] buffer = new byte[1024];
        int bytes;

        boolean fileRead = false;
        FileOutputStream stream = null;
        long size = 0, saved = 0;
        int msgLen;
        File file = null;
        int percent = 0;

        // Keep listening to the InputStream while connected
        while (true) {
            try {

                if (fileRead && size > saved) {
                    bytes = mmInStream.read(buffer);
                    stream.write(buffer, 0, bytes);
                    saved += bytes;
                    int newPercent = (int) ((saved / size) / 100);
                    if (newPercent != percent) {
                        service.mHandler.obtainMessage(Constants.MESSAGE_PERCENT, percent, 0).sendToTarget();
                    }
                    if (saved == size)
                        fileRead = false;
                }

                mmInStream.read(buffer, 0, 4);
                msgLen = toInt(buffer);

                service.sendToast("log: msg lenght" + msgLen);

                bytes = mmInStream.read(buffer, 0, msgLen);

                String received = new String(buffer, 0, bytes);

                if (received.equals(Constants.FILE_START)) {
                    saved = 0;

                    //file name
                    mmInStream.read(buffer, 0, 4);
                    msgLen = toInt(buffer);
                    bytes = mmInStream.read(buffer, 0, msgLen);
                    String name = new String(buffer, 0, bytes);
                    service.sendToast("file : " + name);

                    //file size
                    mmInStream.read(buffer, 0, 8);
                    size = ByteBuffer.wrap(buffer).getLong();

                    //create file
                    file = new File(Environment.getExternalStorageDirectory()
                            .getAbsolutePath() + "/" + name);
                    file.createNewFile();
                    stream = new FileOutputStream(file);

                    service.mHandler.obtainMessage(Constants.MESSAGE_START).sendToTarget();
                    fileRead = true;
                } else if (received.equals(Constants.FILE_END)) {
                    fileRead = false;
                    stream.close();
                    service.mHandler.obtainMessage(Constants.MESSAGE_END, file).sendToTarget();
                    file = null;
                } else if (received.equals(Constants.FILE_ERROR)) {
                    file = null;
                    if (stream != null) stream.close();
                    if (fileRead) service.mHandler.obtainMessage(Constants.MESSAGE_FILE_ERROR).sendToTarget();

                    fileRead = false;
                } else if (fileRead) {
                    stream.write(buffer, 0, bytes);
                    saved += bytes;
                    service.mHandler.obtainMessage(Constants.MESSAGE_PERCENT, (int) ((saved / size) / 100), 0)
                            .sendToTarget();
                } else {
                    // Send the obtained bytes to the UI Activity
                    service.mHandler.obtainMessage(Constants.MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();
                }

            } catch (IOException e) {
                Log.e(TAG, "disconnected", e);
                service.connectionLost();
                // Start the service over to restart listening mode
                service.start();
                break;
            } catch (ArrayIndexOutOfBoundsException ae) {
                Log.e(TAG, "disconnected", ae);
                if (fileRead) {
                    service.mHandler.obtainMessage(Constants.MESSAGE_FILE_ERROR).sendToTarget();
                }
                service.connectionLost();
                service.start();
                break;
            }
        }
    }

    public void writeMsg(byte[] buffer) {
        if (fileWrite)
            return;
        try {

            mmOutStream.write(toBytes(buffer.length));
            mmOutStream.write(buffer);
            // Share the sent message back to the UI Activity
            service.mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, buffer)
                    .sendToTarget();
        } catch (IOException e) {
            Log.e(TAG, "Exception during write", e);
        }
    }

    void write(byte[] buffer, int len) throws IOException {
        mmOutStream.write(toBytes(len));
        mmOutStream.write(buffer, 0, len);
    }

    public void write(byte[] buffer) throws IOException {
            mmOutStream.write(toBytes(buffer.length));
            mmOutStream.write(buffer);
    }

    public void cancel() {
        try {
            mmSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "close() of connect socket failed", e);
        }
    }

    volatile boolean fileWrite;

    /**
     * writes in stream FILE_START -> FILE_NAME + FILE_LENGTH + DATA -> FILE_END
     * @param f
     */

    public void writeFile(final File f) {
        (new Thread() {
            @Override
            public void run() {
                fileWrite = true;
                FileInputStream stream = null;
                try {
                    stream = new FileInputStream(f);
                    byte[] buffer = new byte[1024];
                    int readCount;
                    write(Constants.FILE_START.getBytes());
                    service.sendToast("sending file " + f.getName());

                    write(f.getName().getBytes());  //filename

                    //file size in long
                    ByteBuffer bb = ByteBuffer.allocate(8).putLong(f.length());
                    mmOutStream.write(bb.array());   //write just long

                    while ((readCount = stream.read(buffer)) != -1) {
                        write(buffer, readCount);
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    write(Constants.FILE_END.getBytes());
                    service.sendToast("file sent");
                } catch (Exception e) {
                    e.printStackTrace();
                    try {
                        write(Constants.FILE_ERROR.getBytes());
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                } finally {
                    if (stream != null) {
                        try {
                            stream.close();
                        } catch (IOException e) {
                        }
                    }
                    fileWrite = false;
                }
            }
        }).start();
    }

    private byte[] toBytes(int i) {
        byte[] result = new byte[4];
        result[0] = (byte) (i >> 24);
        result[1] = (byte) (i >> 16);
        result[2] = (byte) (i >> 8);
        result[3] = (byte) (i /*>> 0*/);

        return result;
    }

    private int toInt(byte[] a) {
        return   (a[3] & 0xFF) |
                (a[2] & 0xFF) << 8 |
                (a[1] & 0xFF) << 16 |
                (a[0] & 0xFF) << 24;
    }
}
