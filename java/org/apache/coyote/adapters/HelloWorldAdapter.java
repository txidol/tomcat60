package org.apache.coyote.adapters;

import org.apache.coyote.Adapter;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.coyote.standalone.MessageWriter;

public class HelloWorldAdapter implements Adapter {
    public void service(Request req, Response res) throws Exception {
        MessageWriter out=MessageWriter.getWriter(req, res, 0);
        res.setContentType("text/html");
        
        out.write("<h1>Hello world</h1>");
    }
}
