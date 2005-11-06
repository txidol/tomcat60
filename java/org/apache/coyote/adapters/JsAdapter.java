package org.apache.coyote.adapters;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.coyote.Adapter;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.coyote.http11.Http11BaseProtocol;
import org.apache.coyote.standalone.Main;
import org.apache.coyote.standalone.MessageWriter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.WrappedException;

/**
 * Will load the 'default.js' and execute init. In init you can set params on
 * the server and handler.
 *
 * Entry points:
 * <ul> 
 * <li>init() - called the first time, can set the port and other
 * properties in the "server" object 
 * <li>initThread() - called per thread. 
 * <li>service( req, res, out)
 * </ul>
 * 
 * Defined objects:
 * <ul> 
 * <li>log - commons logger log for this adapter 
 * <li>server - the http connector 
 * <li>jsAdapter, counterAdapter, fileAdapter, mapperAdapter - adapters.
 * </ul>
 * 
 * Note: this is just an example, you can extend it or create your own
 * with different semantics. After the coyote API is cleaned up and converted
 * to NIO - I'll also define a better JS API.
 * 
 */
public class JsAdapter extends Main implements Adapter {

    // Js file to interpret
    static String filename = "js-bin/default.js";

    // to support reloading of the js file
    static long lastModif = 0;

    // Javascript main context and scope
    private Context mainCx;

    private Scriptable mainScope;

    private Log log = LogFactory.getLog("js");

    // we store them per thread. Need a way to manage the tokens
    public static final int SCOPE_NOTE = 12;

    public static final int ADAPTER_NOTES = 11;

    boolean reload = true;

    public JsAdapter() {
    }

    public boolean getReload() {
        return reload;
    }

    public void setReload(boolean reload) {
        this.reload = reload;
    }

    public void service(Request req, final Response res) throws Exception {
        // Per thread.
        Context cx = (Context) req.getNote(JsAdapter.ADAPTER_NOTES);
        Scriptable scope = (Scriptable) req.getNote(JsAdapter.SCOPE_NOTE);
        MessageWriter out = MessageWriter.getWriter(req, res, 0);

        if (cx == null) {
            cx = Context.enter();
            req.setNote(JsAdapter.ADAPTER_NOTES, cx);
            // TODO: exit on thread death

            // Each thread will have an associated context, and an associated
            // scope.
            // The scope will hold the proxies for req, res and the other
            // objects
            // Because the req and response never change for a thread - we don't
            // need to bind them again.

            scope = cx.newObject(mainScope);
            scope.setPrototype(mainScope);

            req.setNote(JsAdapter.SCOPE_NOTE, scope);

            Object fObj = mainScope.get("initThread", mainScope);
            if ((fObj instanceof Function)) {
                Object functionArgs[] = { req, res, out };
                Function f = (Function) fObj;
                Object result = f.call(mainCx, mainScope, mainScope,
                        functionArgs);
            }
        }

        // The file was loaded in initJS(), at server startup. We will only
        // check if it changed, if we are in devel mode and reload it.
        if (reload) {
            load(filename);
        }

        // Now call the service() js function
        Object fObj = ScriptableObject.getProperty(scope, "service");
        if (!(fObj instanceof Function)) {
            log.info("service is undefined or not a function. " + fObj);
            log.info("Not found in scope... " + scope);
            log.info("Parent: " + mainScope.get("service", mainScope));
            fObj = mainScope.get("service", mainScope);
        }
        
        Object functionArgs[] = { req, res, out };
        Function f = (Function) fObj;
        Object result = f.call(cx, scope, scope, functionArgs);
        
    }

    /**
     * Initialize. Will run the init() method in the script
     * 
     * @param proto
     */
    public void initJS() {
        mainCx = Context.enter();
        mainScope = mainCx.initStandardObjects();

        Object jsOut;
        
        jsOut = Context.javaToJS(log, mainScope);
        ScriptableObject.putProperty(mainScope, "log", jsOut);

        jsOut = Context.javaToJS(proto, mainScope);
        ScriptableObject.putProperty(mainScope, "server", jsOut);

        Counters ct = (Counters) proto.getAdapter();
        Mapper mp = (Mapper) ct.getNext();
        FileAdapter fa = (FileAdapter) mp.getDefaultAdapter();

        jsOut = Context.javaToJS(ct, mainScope);
        ScriptableObject.putProperty(mainScope, "countersAdapter", jsOut);

        jsOut = Context.javaToJS(mp, mainScope);
        ScriptableObject.putProperty(mainScope, "mapperAdapter", jsOut);

        jsOut = Context.javaToJS(fa, mainScope);
        ScriptableObject.putProperty(mainScope, "fileAdapter", jsOut);

        jsOut = Context.javaToJS(this, mainScope);
        ScriptableObject.putProperty(mainScope, "jsAdapter", jsOut);

        load(filename);

        Object fObj = mainScope.get("init", mainScope);
        if ((fObj instanceof Function)) {
            Object functionArgs[] = {};
            Function f = (Function) fObj;
            Object result = f.call(mainCx, mainScope, mainScope, functionArgs);
        }
    }

    /**
     * Load a script.
     */
    private void load(String filename) {

        FileReader in = null;
        try {
            File scriptF = new File(filename);

            if (lastModif != 0 && scriptF.lastModified() <= lastModif) {
                // already loaded
                return;
            }
            lastModif = scriptF.lastModified();
            in = new FileReader(filename);
        } catch (FileNotFoundException ex) {
            System.err.println("JS file not found " + ex);
            return;
        }

        try {
            // Here we evalute the entire contents of the file as
            // a script. Text is printed only if the print() function
            // is called.
            Object result = mainCx.evaluateReader(mainScope, in, filename, 1,
                    null);
        } catch (WrappedException we) {
            System.err.println(we.getWrappedException().toString());
            we.printStackTrace();
        } catch (EvaluatorException ee) {
            System.err.println("js: " + ee.getMessage());
        } catch (JavaScriptException jse) {
            System.err.println("js: " + jse.getMessage());
        } catch (IOException ioe) {
            System.err.println(ioe.toString());
        } finally {
            try {
                in.close();
            } catch (IOException ioe) {
                System.err.println(ioe.toString());
            }
        }
    }

    public void setProtocol(Http11BaseProtocol proto) {
        this.proto = proto;
    }

    // ------------ Example on how to run it --------------------

    public void init() {
        super.init();
        JsAdapter js = this; // new JsAdapter();
        js.setProtocol(proto);
        js.initJS();
        mainAdapter.addAdapter("/js-bin", js);
    }

    public static void main(String args[]) {
        JsAdapter sa = new JsAdapter();
        sa.run(); // in Main - will call init first
    }

}