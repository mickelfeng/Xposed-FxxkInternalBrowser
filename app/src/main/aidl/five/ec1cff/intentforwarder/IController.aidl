// IController.aidl
package five.ec1cff.intentforwarder;

// Declare any non-default types here with import statements

interface IController {
    void setState(boolean isRunning);
    boolean getState();
}