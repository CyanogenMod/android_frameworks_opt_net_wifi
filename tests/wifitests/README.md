# Wifi Unit Tests
This package contains unit tests for the android wifi service based on the
[Android Testing Support Library](http://developer.android.com/tools/testing-support-library/index.html).
The test cases are built using the [JUnit](http://junit.org/) and [Mockito](http://mockito.org/)
libraries.

## Running Tests
The easiest way to run tests is simply run

```
runtest frameworks-wifi
```

`runtest` will build the test project and push the APK to the connected device. It will then run the
tests on the device. See `runtest --help` for options to specify individual test classes or methods.

**WARNING:** You have to build wifi-service first before you run runtest for changes there to take
effect. You can use the following command from your build root to build the wifi service and run
tests.

```
mmm frameworks/opt/net/wifi/service && runtest frameworks-wifi
```


If you manually build and push the APK to the device you can run tests using

```
adb shell am instrument -w 'com.android.server.wifi.test/android.support.test.runner.AndroidJUnitRunner'
```

## Adding Tests
Tests can be added by adding classes to the src directory. JUnit4 style test cases can
be written by simply annotating test methods with `org.junit.Test`.
