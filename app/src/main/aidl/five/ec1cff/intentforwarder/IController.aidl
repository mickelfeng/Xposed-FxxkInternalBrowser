package five.ec1cff.intentforwarder;

interface IController {
    void setState(boolean isRunning);
    boolean getState();

    String dumpService();
    void dumpJson();
    void loadJson();
}
