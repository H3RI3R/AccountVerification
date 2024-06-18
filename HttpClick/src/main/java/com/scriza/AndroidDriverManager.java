package com.scriza;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class AndroidDriverManager {
    private static final Map<String, AndroidDriver> drivers = new HashMap<>();
    private static final Map<String, String> udidToServerUrl = new HashMap<>();

    static {
        udidToServerUrl.put("emulator-5554", "http://127.0.0.1:4723/wd/hub");
//        udidToServerUrl.put("emulator-5556", "http://127.0.0.1:4726/wd/hub");
    }

    public static synchronized AndroidDriver getDriver(String udid) throws MalformedURLException {
        if (!drivers.containsKey(udid) || isSessionEnded(drivers.get(udid))) {
            UiAutomator2Options options = new UiAutomator2Options()
                    .setDeviceName("android 29 w2")
                    .setUdid(udid)
                    .setPlatformName("Android")
                    .setPlatformVersion("10");

            AndroidDriver driver = new AndroidDriver(new URL(udidToServerUrl.get(udid)), options);
            drivers.put(udid, driver);
        }
        return drivers.get(udid);
    }

    private static boolean isSessionEnded(RemoteWebDriver driver) {
        try {
            driver.getSessionId();
            return false;
        } catch (org.openqa.selenium.NoSuchSessionException e) {
            return true;
        }
    }

    public static synchronized void quitDriver(String udid) {
        if (drivers.containsKey(udid)) {
            drivers.get(udid).quit();
            drivers.remove(udid);
        }
    }

    public static synchronized void quitAllDrivers() {
        for (String udid : drivers.keySet()) {
            drivers.get(udid).quit();
        }
        drivers.clear();
    }
}
