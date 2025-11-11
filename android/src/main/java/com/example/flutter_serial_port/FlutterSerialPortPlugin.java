package com.example.flutter_serial_port;

import java.lang.Thread;
import java.lang.Runnable;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import io.flutter.plugin.common.EventChannel;


import android.serialport.SerialPort;
import android.serialport.SerialPortFinder;

/** FlutterSerialPortPlugin */
public class FlutterSerialPortPlugin implements FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler  {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private MethodChannel channel;
  private EventChannel eventChannel;

  private static final String TAG = "FlutterSerialPortPlugin";
  private SerialPortFinder mSerialPortFinder = new SerialPortFinder();
  protected SerialPort mSerialPort;
  protected OutputStream mOutputStream;
  private InputStream mInputStream;
  private ReadThread mReadThread;
  private EventChannel.EventSink mEventSink;
  private Handler mHandler = new Handler(Looper.getMainLooper());;

  private class ReadThread extends Thread {
    private volatile boolean isRunning = true;

    public void stopReading() {
      isRunning = false;
      interrupt();
    }

    @Override
    public void run() {
      super.run();
      while (isRunning && !isInterrupted()) {
        int size;
        try {
          byte[] buffer = new byte[64];
          if (mInputStream == null) {
            break;
          }
          size = mInputStream.read(buffer);
          // Log.d(TAG, "read size: " + String.valueOf(size));
          if (size > 0) {
            onDataReceived(buffer, size);
          }
        } catch (IOException e) {
          if (isRunning) {
            Log.e(TAG, "Error reading from serial port: " + e.getMessage());
          }
          break;
        }
      }
      Log.d(TAG, "ReadThread stopped");
    }
  }

  protected void onDataReceived(final byte[] buffer, final int size) {
    if (mEventSink != null) {
      mHandler.post(new Runnable() {
        @Override
        public void run() {
          // Log.d(TAG, "eventsink: " + buffer.toString());
          mEventSink.success(Arrays.copyOfRange(buffer, 0, size));
        }
      });
    }
  }




  @Override
  public void onMethodCall(MethodCall call, Result result) {
    switch (call.method) {
    case "getPlatformVersion":
      result.success("Android " + android.os.Build.VERSION.RELEASE);
      break;
    case "open":
      final String devicePath = call.argument("devicePath");
      final int baudrate = call.argument("baudrate");
      Log.d(TAG, "Open " + devicePath + ", baudrate: " + baudrate);
      Boolean openResult = openDevice(devicePath, baudrate);
      result.success(openResult);
      break;
    case "close":
      Boolean closeResult = closeDevice();
      result.success(closeResult);
      break;
    case "write":
      writeData((byte[]) call.argument("data"));
      result.success(true);
      break;
    case "getAllDevices":
      ArrayList<String> devices = getAllDevices();
      Log.d(TAG, devices.toString());
      result.success(devices);
      break;
    case "getAllDevicesPath":
      ArrayList<String> devicesPath = getAllDevicesPath();
      Log.d(TAG, devicesPath.toString());
      result.success(devicesPath);
      break;
    default:
      result.notImplemented();
      break;
    }
  }

  @Override
  public void onListen(Object o, EventChannel.EventSink eventSink) {
    mEventSink = eventSink;
  }

  @Override
  public void onCancel(Object o) {
    mEventSink = null;
  }

  private ArrayList<String> getAllDevices() {
    ArrayList<String> devices = new ArrayList<String>(Arrays.asList(mSerialPortFinder.getAllDevices()));
    return devices;
  }

  private ArrayList<String> getAllDevicesPath() {
    ArrayList<String> devicesPath = new ArrayList<String>(Arrays.asList(mSerialPortFinder.getAllDevicesPath()));
    return devicesPath;
  }

  private Boolean openDevice(String devicePath, int baudrate) {
    if (mSerialPort == null) {
      /* Check parameters */
      if ((devicePath.length() == 0) || (baudrate == -1)) {
        return false;
      }

      /* Open the serial port */
      try {
        // mSerialPort = new SerialPort(new File(devicePath), baudrate, 0);
		mSerialPort = new SerialPort(new File(devicePath), baudrate);
        mOutputStream = mSerialPort.getOutputStream();
        mInputStream = mSerialPort.getInputStream();
        mReadThread = new ReadThread();
        mReadThread.start();
        return true;
      } catch (Exception e) {
        Log.e(TAG, e.toString());
        return false;
      }
    }
    return false;
  }

  private Boolean closeDevice() {
    if (mSerialPort != null) {
      // Stop the reading thread first
      if (mReadThread != null) {
        mReadThread.stopReading();
        try {
          mReadThread.join(1000); // Wait max 1 second for thread to finish
        } catch (InterruptedException e) {
          Log.w(TAG, "Interrupted while waiting for ReadThread to stop");
          Thread.currentThread().interrupt();
        }
        mReadThread = null;
      }

      // Close streams
      try {
        if (mInputStream != null) {
          mInputStream.close();
          mInputStream = null;
        }
      } catch (IOException e) {
        Log.e(TAG, "Error closing input stream: " + e.getMessage());
      }

      try {
        if (mOutputStream != null) {
          mOutputStream.close();
          mOutputStream = null;
        }
      } catch (IOException e) {
        Log.e(TAG, "Error closing output stream: " + e.getMessage());
      }

      // Close serial port
      mSerialPort.close();
      mSerialPort = null;

      Log.d(TAG, "Serial port closed successfully");
      return true;
    }
    return false;
  }

  private void writeData(byte[] data) {
    if (mOutputStream == null) {
      Log.e(TAG, "Cannot write: output stream is null");
      return;
    }
    try {
      mOutputStream.write(data);
      // mOutputStream.write('\n');
    } catch (IOException e) {
      Log.e(TAG, "Error writing to serial port: " + e.getMessage());
    }
  }
  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "serial_port/event");
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "serial_port");
    channel.setMethodCallHandler(this);
    eventChannel.setStreamHandler(this);
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    // Clean up serial port resources
    closeDevice();

    // Clear event sink to prevent memory leaks
    mEventSink = null;

    // Clean up channels
    if (channel != null) {
      channel.setMethodCallHandler(null);
      channel = null;
    }
    if (eventChannel != null) {
      eventChannel.setStreamHandler(null);
      eventChannel = null;
    }

    Log.d(TAG, "Plugin detached and resources cleaned up");
  }
}