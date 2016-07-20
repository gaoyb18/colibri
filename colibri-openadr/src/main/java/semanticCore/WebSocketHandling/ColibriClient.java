package semanticCore.WebSocketHandling;

import Utils.*;
import org.atmosphere.wasync.Socket;
import semanticCore.MsgObj.ColibriMessage;
import semanticCore.MsgObj.ContentMsgObj.AddMsg;
import semanticCore.MsgObj.ContentMsgObj.RegisterMsg;
import semanticCore.MsgObj.ContentMsgObj.PutMsg;
import semanticCore.MsgObj.MsgType;

import javax.xml.bind.*;
import java.io.IOException;
import java.net.ConnectException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by georg on 02.07.16.
 */
public class ColibriClient {
    // This is the actual socket which is responsible for the message exchange.
    private Socket socket;
    // Used to generate send colibri messages
    private GenerateSendMessage genSendMessage;
    // Used to process received colibri messages
    private ProcessReceivedMsg processMessage;
    // This object bridges the colibri part with the openADR part and vice-versa.
    private OpenADRColibriBridge bridge;
    // This HaspMap stores all the sended messages as long as no reply is received
    private Map<String, ColibriMessage> sendedMsgToColCore;
    private JAXBContext jaxbContext;
    private Marshaller jaxbMarshaller;
    private Unmarshaller jaxbUnmarshaller;

    private TimeoutWatcher timeoutWatcher;

    /* This HashMap contains all services which the semantic core knows.
        The key is the colibri core service and the value is the follow service for the connector.
        This follow service is used the inform the connector how it should react on new information at the colibri core service. */
    private HashMap<String, String> knownConnectorToColibriServices;
    // This HashMap contains all services which the semantic core observes
    private List<String> observedConnectorToColibriServices;

    // true...when the connector is proper registered, false...otherwise
    private boolean registered = false;
    private MyLock terminateLock;
    private AtomicBoolean terminateTimeout;

    // This string represents the base URL for all services
    private String serviceBaseURL;

    public ColibriClient(OpenADRColibriBridge bridge){
        try {
            jaxbContext = JAXBContext.newInstance(RegisterMsg.class, AddMsg.class, PutMsg.class);
            jaxbMarshaller = jaxbContext.createMarshaller();
            jaxbUnmarshaller = jaxbContext.createUnmarshaller();

            // output pretty printed
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            jaxbMarshaller.setProperty("com.sun.xml.internal.bind.characterEscapeHandler", new DummyEscapeHandler());

            jaxbMarshaller.setProperty("com.sun.xml.internal.bind.xmlHeaders",
                    "<!DOCTYPE rdf:RDF [\n" +
                            "<!ENTITY xsd \"http://www.w3.org/2001/XMLSchema#\" >\n" +
                            "<!ENTITY colibri \"https://raw.githubusercontent.com/dschachinger/colibri/master/res/colibri.owl#\">]>");
        } catch (PropertyException e) {
            e.printStackTrace();
        } catch (JAXBException e) {
            e.printStackTrace();
        }

        knownConnectorToColibriServices = new HashMap<>();
        observedConnectorToColibriServices = new ArrayList<>();

        try {
            socket = InitWebsocket.initWebSocket(this);
        } catch (ConnectException e) {
            System.err.println("Exception: Connection establishment to colibri server refused. Maybe it is not running");
            System.exit(1);
        } catch (IOException e) {
            e.printStackTrace();
        }
        genSendMessage = new GenerateSendMessage(jaxbMarshaller);
        sendedMsgToColCore = Collections.synchronizedMap(new HashMap<String, ColibriMessage>());
        timeoutWatcher = TimeoutWatcher.initColibriTimeoutWatcher(30000, this);
        this.processMessage = new ProcessReceivedMsg(this);
        this.bridge = bridge;
        this.terminateLock = new MyLock();
        terminateTimeout = new AtomicBoolean(true);
    }

    public GenerateSendMessage getGenSendMessage() {
        return genSendMessage;
    }

    /**
     * @return a list of all services which the colibri core observes.
     */
    public List<String> getObservedConnectorToColibriServices() {
        return observedConnectorToColibriServices;
    }

    /**
     * @return a list of all services which the colibri core observes.
     */
    public HashMap<String, String> getKnownServicesHashMap() {
        return knownConnectorToColibriServices;
    }

    public boolean isRegistered() {
        return registered;
    }

    /**
     * This method is responsible to transmit a given colibri message to the colibri core.
     * @param sendMsg given colibri message
     */
    public void sendColibriMsg(ColibriMessage sendMsg){
        if(!sendMsg.getMsgType().equals(MsgType.STATUS)){
            sendedMsgToColCore.put(sendMsg.getHeader().getMessageId(), sendMsg);
            timeoutWatcher.addMonitoredMsg(sendMsg.getHeader().getMessageId());
        }
        try {
            socket.fire(new Message("openADR", sendMsg.toMsgString()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This method is responsible to process a given received colibri message from the colibri core.
     * @param msg given colibri message
     */
    public void processReceivedMsg(ColibriMessage msg){
        Pair<Boolean, List<ColibriMessage>> result = processMessage.processColMsg(msg);

        boolean sendToOpenADR = result.getFst();

        List<ColibriMessage> replies = result.getSnd();
        if(replies != null){
            for(ColibriMessage reply : replies){
                sendColibriMsg(reply);
            }
        }

        if(sendToOpenADR){
            bridge.informationFlowFromColibriToOpenADR(msg);
        }

    }

    public void sendRegisterMessage(){
        if(!registered) {
            sendColibriMsg(genSendMessage.gen_REGISTER());
        }
        else {
            System.out.println("connector is already registered");
        }
    }

    public void sendDeregisterMessage(){
        if(registered) {
            sendColibriMsg(genSendMessage.gen_DEREGISTER());
        }
        else {
            System.out.println("connector is already deregistered");
        }
    }

    public void sendAddService(EventType eventType){
        if(!registered){
            System.out.println("connector is not registered");
        }

        sendColibriMsg(genSendMessage.gen_ADD_SERVICE(eventType, serviceBaseURL));
    }

    public Map<String, ColibriMessage> getSendedMsgToColCore() {
        return sendedMsgToColCore;
    }

    public void setRegistered(boolean registered) {
        this.registered = registered;
    }

    public String getServiceBaseURL() {
        return serviceBaseURL;
    }

    public void setServiceBaseURL(String serviceBaseURL) {
        this.serviceBaseURL = serviceBaseURL;
    }

    public Unmarshaller getJaxbUnmarshaller() {
        return jaxbUnmarshaller;
    }

    /**
     * This method terminates the client.
     * It is not guaranteed that this client still works afterwards.
     * It is only allowed to call this method if the party was successfully started beforehand.
     */
    public void terminate(){
        new DeregisterThread().start();
    }

    //--------------------------- inner class DeregisterThread ---------------------------------//

    /**
     * This class is used to start a new thread to shut down the colibri client.
     * A new thread is started because you have to deregister the client at the server side and
     * you have to wait until the server responses with an okay status message.
     */
    public class DeregisterThread extends Thread{

        @Override
        public void run() {
            if(registered) {
                sendDeregisterMessage();
                System.out.println("wait");
                waitForDeregistration();
                System.out.println("go on");
            }
            socket.close();
        }
    }

    public void successfulDeregisteredGoOnWithTermination(){
        synchronized (terminateLock){
            terminateTimeout.set(false);
            terminateLock.notifyAll();

        }
    }

    private void waitForDeregistration(){
        synchronized (terminateLock){
            try {
                // 5 seconds timeout
                terminateLock.wait(50000);
                if(terminateTimeout.compareAndSet(true,false)) {
                    registered = false;
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
