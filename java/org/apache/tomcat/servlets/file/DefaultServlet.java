/*
 * Copyright 1999,2004-2006 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.tomcat.servlets.file;


import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.StringTokenizer;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.util.ServerInfo;
import org.apache.tomcat.util.res.StringManager;

/**
 * The default resource-serving servlet for most web applications,
 * used to serve static resources such as HTML pages and images.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class DefaultServlet  extends HttpServlet {


    // ----------------------------------------------------- Instance Variables
    /**
     * HTTP date format.
     */
    protected static final SimpleDateFormat format =
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);


    /**
     * Should we generate directory listings?
     */
    protected boolean listings = true;


    /**
     * Read only flag. By default, it's set to true.
     */
    protected boolean readOnly = true;


    /**
     * The input buffer size to use when serving resources.
     */
    protected int input = 2048;

    /**
     * The output buffer size to use when serving resources.
     */
    protected int output = 2048;

    /**
     * Size of file transfer buffer in bytes.
     */
    protected static final int BUFFER_SIZE = 4096;

    /**
     * Array containing the safe characters set.
     */
    protected static URLEncoder urlEncoder;

    /**
     * Allow a readme file to be included.
     */
    protected String readmeFile = null;

    /**
     * File encoding to be used when reading static files. If none is specified
     * the platform default is used.
     */
    protected String fileEncoding = null;
        
    /**
     * Full range marker.
     */
    protected static ArrayList FULL = new ArrayList();
    
    protected File basePath;
    protected String basePathName;
    
    // ----------------------------------------------------- Static Initializer


    /**
     * GMT timezone - all HTTP dates are on GMT
     */
    static {
        urlEncoder = new URLEncoder();
        urlEncoder.addSafeCharacter('-');
        urlEncoder.addSafeCharacter('_');
        urlEncoder.addSafeCharacter('.');
        urlEncoder.addSafeCharacter('*');
        urlEncoder.addSafeCharacter('/');
    }


    /**
     * MIME multipart separation string
     */
    protected static final String mimeSeparation = "TOMCAT_MIME_BOUNDARY";

    /**
     * The string manager for this package.
     */
    protected static StringManager sm =
        StringManager.getManager("org.apache.tomcat.servlets.file");



    // --------------------------------------------------------- Public Methods


    /**
     * Finalize this servlet.
     */
    public void destroy() {
    }


    /**
     * Initialize this servlet.
     */
    public void init() throws ServletException {
        String realPath = getServletContext().getRealPath("/");
        basePath = new File(realPath);
        try {
            basePathName = basePath.getCanonicalPath();
        } catch (IOException e) {
            basePathName = basePath.getAbsolutePath();
        }
        
        // Set our properties from the initialization parameters
        String value = null;
        try {
            value = getServletConfig().getInitParameter("input");
            input = Integer.parseInt(value);
        } catch (Throwable t) {
            ;
        }
        try {
            value = getServletConfig().getInitParameter("listings");
            if (value != null )
                listings = (new Boolean(value)).booleanValue();
        } catch (Throwable t) {
            ;
        }
        try {
            value = getServletConfig().getInitParameter("readonly");
            if (value != null)
                readOnly = (new Boolean(value)).booleanValue();
        } catch (Throwable t) {
            ;
        }
        try {
            value = getServletConfig().getInitParameter("output");
            output = Integer.parseInt(value);
        } catch (Throwable t) {
            ;
        }
        try {
            value = getServletConfig().getInitParameter("fileEncoding");
            fileEncoding = value;
        } catch (Throwable t) {
            ;
        }

        
        // Sanity check on the specified buffer sizes
        if (input < 256)
            input = 256;
        if (output < 256)
            output = 256;

    }


    // ------------------------------------------------------ Protected Methods

    public static final String INCLUDE_REQUEST_URI_ATTR =
        "javax.servlet.include.request_uri";
    public static final String INCLUDE_SERVLET_PATH_ATTR =
        "javax.servlet.include.servlet_path";
    public static final String INCLUDE_PATH_INFO_ATTR =
        "javax.servlet.include.path_info";
    public static final String INCLUDE_CONTEXT_PATH_ATTR =
        "javax.servlet.include.context_path";

    
    /**
     * Return the relative path associated with this servlet.
     *
     * @param request The servlet request we are processing
     */
    public static String getRelativePath(HttpServletRequest request) {

        // Are we being processed by a RequestDispatcher.include()?
        if (request.getAttribute(INCLUDE_REQUEST_URI_ATTR) != null) {
            String result = (String) request.getAttribute(
                                            INCLUDE_PATH_INFO_ATTR);
            if (result == null)
                result = (String) request.getAttribute(
                                            INCLUDE_SERVLET_PATH_ATTR);
            if ((result == null) || (result.equals("")))
                result = "/";
            return (result);
        }

        // No, extract the desired path directly from the request
        // For 'default' servlet, the path info contains the path
        // if this is mapped to serve a subset of the files - we
        // need both
        
        String result = request.getPathInfo();
        if (result == null) {
            result = request.getServletPath();
        } else {
            result = request.getServletPath() + result;
        }
        if ((result == null) || (result.equals(""))) {
            result = "/";
        }
        return (result);

    }


    /**
     * Process a GET request for the specified resource.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet-specified error occurs
     */
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
        throws IOException, ServletException {

        // Serve the requested resource, including the data content
        serveResource(request, response, true);

    }


    /**
     * Process a HEAD request for the specified resource.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet-specified error occurs
     */
    protected void doHead(HttpServletRequest request,
                          HttpServletResponse response)
        throws IOException, ServletException {

        // Serve the requested resource, without the data content
        serveResource(request, response, false);

    }


    /**
     * Process a POST request for the specified resource.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet-specified error occurs
     */
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
        throws IOException, ServletException {
        doGet(request, response);
    }


    /**
     * Process a POST request for the specified resource.
     *
     * @param req The servlet request we are processing
     * @param resp The servlet response we are creating
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet-specified error occurs
     */
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {

        if (readOnly) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String path = getRelativePath(req);

        if (path.indexOf("..") >= 0) {
            // not supported, too dangerous
            // what else to escape ?
            resp.setStatus(404);
            return;
        }

        File resFile = new File(basePath, path);
        boolean exists = resFile.exists();
        

        // extra check
        if (!resFile.getCanonicalPath().startsWith(basePathName)) {
            //log.info("File outside basedir " + basePathS + " " + f);
            resp.setStatus(404);
            return;
        }


        boolean result = true;

        // Temp. content file used to support partial PUT
        File contentFile = null;

        Range range = parseContentRange(req, resp);

        InputStream resourceInputStream = null;

        // Append data specified in ranges to existing content for this
        // resource - create a temp. file on the local filesystem to
        // perform this operation
        // Assume just one range is specified for now
        if (range != null) {
            //contentFile = executePartialPut(req, range, path);
            //resourceInputStream = new FileInputStream(contentFile);
            resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
            return;
        } else {
            resourceInputStream = req.getInputStream();
        }

        try {
            // will override 
            FileOutputStream fos = new FileOutputStream(resFile);
            copy(resourceInputStream, fos);
        } catch(IOException e) {
            result = false;
        }

        if (result) {
            if (exists) {
                resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            } else {
                resp.setStatus(HttpServletResponse.SC_CREATED);
            }
        } else {
            resp.sendError(HttpServletResponse.SC_CONFLICT);
        }

    }


    /**
     * Handle a partial PUT.  New content specified in request is appended to
     * existing content in oldRevisionContent (if present). This code does
     * not support simultaneous partial updates to the same resource.
     */
//    protected File executePartialPut(HttpServletRequest req, Range range,
//                                     String path)
//        throws IOException {
//
//        // Append data specified in ranges to existing content for this
//        // resource - create a temp. file on the local filesystem to
//        // perform this operation
//        File tempDir = (File) getServletContext().getAttribute
//            ("javax.servlet.context.tempdir");
//        // Convert all '/' characters to '.' in resourcePath
//        String convertedResourcePath = path.replace('/', '.');
//        File contentFile = new File(tempDir, convertedResourcePath);
//        if (contentFile.createNewFile()) {
//            // Clean up contentFile when Tomcat is terminated
//            contentFile.deleteOnExit();
//        }
//
//        RandomAccessFile randAccessContentFile =
//            new RandomAccessFile(contentFile, "rw");
//
//        Resource oldResource = null;
//        try {
//            Object obj = resources.lookup(path);
//            if (obj instanceof Resource)
//                oldResource = (Resource) obj;
//        } catch (NamingException e) {
//        }
//
//        // Copy data in oldRevisionContent to contentFile
//        if (oldResource != null) {
//            BufferedInputStream bufOldRevStream =
//                new BufferedInputStream(oldResource.streamContent(),
//                                        BUFFER_SIZE);
//
//            int numBytesRead;
//            byte[] copyBuffer = new byte[BUFFER_SIZE];
//            while ((numBytesRead = bufOldRevStream.read(copyBuffer)) != -1) {
//                randAccessContentFile.write(copyBuffer, 0, numBytesRead);
//            }
//
//            bufOldRevStream.close();
//        }
//
//        randAccessContentFile.setLength(range.length);
//
//        // Append data in request input stream to contentFile
//        randAccessContentFile.seek(range.start);
//        int numBytesRead;
//        byte[] transferBuffer = new byte[BUFFER_SIZE];
//        BufferedInputStream requestBufInStream =
//            new BufferedInputStream(req.getInputStream(), BUFFER_SIZE);
//        while ((numBytesRead = requestBufInStream.read(transferBuffer)) != -1) {
//            randAccessContentFile.write(transferBuffer, 0, numBytesRead);
//        }
//        randAccessContentFile.close();
//        requestBufInStream.close();
//
//        return contentFile;
//
//    }


    /**
     * Process a POST request for the specified resource.
     *
     * @param req The servlet request we are processing
     * @param resp The servlet response we are creating
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet-specified error occurs
     */
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {

        if (readOnly) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String path = getRelativePath(req);

        File resFile = new File(basePath, path);

        boolean exists = resFile.exists();

        if (exists) {
            boolean result = resFile.delete();
            if (result) {
                resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            } else {
                resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }

    }


    /**
     * Check if the conditions specified in the optional If headers are
     * satisfied.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     * @param resourceAttributes The resource information
     * @return boolean true if the resource meets all the specified conditions,
     * and false if any of the conditions is not satisfied, in which case
     * request processing is stopped
     */
    protected boolean checkIfHeaders(HttpServletRequest request,
                                     HttpServletResponse response,
                                     File resourceAttributes)
        throws IOException {

        return checkIfMatch(request, response, resourceAttributes)
            && checkIfModifiedSince(request, response, resourceAttributes)
            && checkIfNoneMatch(request, response, resourceAttributes)
            && checkIfUnmodifiedSince(request, response, resourceAttributes);

    }


    /**
     * Get the ETag associated with a file.
     *
     * @param resourceAttributes The resource information
     */
    protected String getETag(File resourceAttributes) {
        String result = null;
//        if ((result = resourceAttributes.getETag(true)) != null) {
//            return result;
//        } else if ((result = resourceAttributes.getETag()) != null) {
//            return result;
//        } else {
            return "W/\"" + resourceAttributes.length() + "-"
                + resourceAttributes.lastModified() + "\"";
//        }
    }


    /**
     * URL rewriter.
     *
     * @param path Path which has to be rewiten
     */
    protected String rewriteUrl(String path) {
        return urlEncoder.encode( path );
    }



    /**
     * Serve the specified resource, optionally including the data content.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     * @param content Should the content be included?
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet-specified error occurs
     */
    protected void serveResource(HttpServletRequest request,
                                 HttpServletResponse response,
                                 boolean content)
        throws IOException, ServletException {

        // Identify the requested resource path
        String path = getRelativePath(request);

        File resFile = new File(basePath, path);

        if (!resFile.exists()) {
            send404(request, response);
            return;
        }

        boolean isDir = resFile.isDirectory();
        
        if (isDir) {
            getServletContext();
            // Skip directory listings if we have been configured to
            // suppress them
            if (!listings) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND,
                                   request.getRequestURI());
                return;
            }
            String contentType = "text/html;charset=UTF-8";
            
            renderDir(request, response, resFile, content );
            
            return;
        }
        
        // If the resource is not a collection, and the resource path
        // ends with "/" or "\", return NOT FOUND
        if (path.endsWith("/") || (path.endsWith("\\"))) {
            // Check if we're included so we can return the appropriate 
            // missing resource name in the error
            String requestUri = (String) request.getAttribute(
                    INCLUDE_REQUEST_URI_ATTR);
            if (requestUri == null) {
                requestUri = request.getRequestURI();
            }
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    requestUri);
            return;
        }

        // Check if the conditions specified in the optional If headers are
        // satisfied.

        // Checking If headers
        boolean included =
            (request.getAttribute(INCLUDE_CONTEXT_PATH_ATTR) != null);
        if (!included
                && !checkIfHeaders(request, response, resFile)) {
            return;
        }


        // Find content type.
        String contentType = getServletContext().getMimeType(resFile.getName());

        ArrayList ranges = null;
        long contentLength = -1L;

        // Parse range specifier

        ranges = parseRange(request, response, resFile);

        // ETag header
        response.setHeader("ETag", getETag(resFile));

        // TODO: remove the sync, optimize - it's from ResourceAttribute
        String lastModifiedHttp = lastModifiedHttp(resFile);

        // Last-Modified header
        response.setHeader("Last-Modified", lastModifiedHttp);

        // Get content length
        contentLength = resFile.length();
        // Special case for zero length files, which would cause a
        // (silent) ISE when setting the output buffer size
        if (contentLength == 0L) {
            content = false;
        }

        ServletOutputStream ostream = null;
        PrintWriter writer = null;

        if (content) {

            // Trying to retrieve the servlet output stream

            try {
                ostream = response.getOutputStream();
            } catch (IllegalStateException e) {
                // If it fails, we try to get a Writer instead if we're
                // trying to serve a text file
                if ( (contentType == null)
                     || (contentType.startsWith("text")) ) {
                    writer = response.getWriter();
                } else {
                    throw e;
                }
            }
        }

        if ( ( ((ranges == null) || (ranges.isEmpty()))
                && (request.getHeader("Range") == null) )
                || (ranges == FULL) ) {

            // Set the appropriate output headers
            if (contentType != null) {
                response.setContentType(contentType);
            }
            if ((contentLength >= 0)) {
                if (contentLength < Integer.MAX_VALUE) {
                    response.setContentLength((int) contentLength);
                } else {
                    // Set the content-length as String to be able to use a long
                    response.setHeader("content-length", "" + contentLength);
                }
            }

            // Copy the input stream to our output stream (if requested)
            if (content) {
                try {
                    response.setBufferSize(output);
                } catch (IllegalStateException e) {
                    // Silent catch
                }
                if (ostream != null) {
                    copy(resFile, null, ostream);
                } else {
                    copy(resFile, null, writer);
                }
            }

        } else {

            if ((ranges == null) || (ranges.isEmpty()))
                return;

            // Partial content response.

            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);

            if (ranges.size() == 1) {

                Range range = (Range) ranges.get(0);
                response.addHeader("Content-Range", "bytes "
                                   + range.start
                                   + "-" + range.end + "/"
                                   + range.length);
                long length = range.end - range.start + 1;
                if (length < Integer.MAX_VALUE) {
                    response.setContentLength((int) length);
                } else {
                    // Set the content-length as String to be able to use a long
                    response.setHeader("content-length", "" + length);
                }

                if (contentType != null) {
                    response.setContentType(contentType);
                }

                if (content) {
                    try {
                        response.setBufferSize(output);
                    } catch (IllegalStateException e) {
                        // Silent catch
                    }
                    if (ostream != null) {
                        copy(resFile, ostream, range);
                    } else {
                        copy(resFile, writer, range);
                    }
                }

            } else {

                response.setContentType("multipart/byteranges; boundary="
                                        + mimeSeparation);

                if (content) {
                    try {
                        response.setBufferSize(output);
                    } catch (IllegalStateException e) {
                        // Silent catch
                    }
                    if (ostream != null) {
                        copy(resFile, ostream, ranges.iterator(),
                             contentType);
                    } else {
                        copy(resFile, writer, ranges.iterator(),
                             contentType);
                    }
                }

            }

        }

    }


    protected String lastModifiedHttp(File resFile) {
        String lastModifiedHttp = null;
        // TODO: use thread local, no sync lock
        synchronized (format) {
            lastModifiedHttp = format.format(new Date(resFile.lastModified()));
        }
        return lastModifiedHttp;
    }


    protected void send404(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Check if we're included so we can return the appropriate 
        // missing resource name in the error
        String requestUri = (String) request.getAttribute(
                                        INCLUDE_REQUEST_URI_ATTR);
        if (requestUri == null) {
            requestUri = request.getRequestURI();
        } else {
            // We're included, and the response.sendError() below is going
            // to be ignored by the resource that is including us.
            // Therefore, the only way we can let the including resource
            // know is by including warning message in response
            response.getWriter().write(
                sm.getString("defaultServlet.missingResource",
                requestUri));
        }

        response.sendError(HttpServletResponse.SC_NOT_FOUND,
                           requestUri);
        return;
    }


    /**
     * Parse the content-range header.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     * @return Range
     */
    protected Range parseContentRange(HttpServletRequest request,
                                      HttpServletResponse response)
        throws IOException {

        // Retrieving the content-range header (if any is specified
        String rangeHeader = request.getHeader("Content-Range");

        if (rangeHeader == null)
            return null;

        // bytes is the only range unit supported
        if (!rangeHeader.startsWith("bytes")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

        rangeHeader = rangeHeader.substring(6).trim();

        int dashPos = rangeHeader.indexOf('-');
        int slashPos = rangeHeader.indexOf('/');

        if (dashPos == -1) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

        if (slashPos == -1) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

        Range range = new Range();

        try {
            range.start = Long.parseLong(rangeHeader.substring(0, dashPos));
            range.end =
                Long.parseLong(rangeHeader.substring(dashPos + 1, slashPos));
            range.length = Long.parseLong
                (rangeHeader.substring(slashPos + 1, rangeHeader.length()));
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

        if (!range.validate()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

        return range;

    }


    /**
     * Parse the range header.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     * @return Vector of ranges
     */
    protected ArrayList parseRange(HttpServletRequest request,
                                HttpServletResponse response,
                                File resourceAttributes)
        throws IOException {

        // Checking If-Range
        String headerValue = request.getHeader("If-Range");

        if (headerValue != null) {

            long headerValueTime = (-1L);
            try {
                headerValueTime = request.getDateHeader("If-Range");
            } catch (Exception e) {
                ;
            }

            String eTag = getETag(resourceAttributes);
            long lastModified = resourceAttributes.lastModified();

            if (headerValueTime == (-1L)) {

                // If the ETag the client gave does not match the entity
                // etag, then the entire entity is returned.
                if (!eTag.equals(headerValue.trim()))
                    return FULL;

            } else {

                // If the timestamp of the entity the client got is older than
                // the last modification date of the entity, the entire entity
                // is returned.
                if (lastModified > (headerValueTime + 1000))
                    return FULL;

            }

        }

        long fileLength = resourceAttributes.length();

        if (fileLength == 0)
            return null;

        // Retrieving the range header (if any is specified
        String rangeHeader = request.getHeader("Range");

        if (rangeHeader == null)
            return null;
        // bytes is the only range unit supported (and I don't see the point
        // of adding new ones).
        if (!rangeHeader.startsWith("bytes")) {
            response.addHeader("Content-Range", "bytes */" + fileLength);
            response.sendError
                (HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            return null;
        }

        rangeHeader = rangeHeader.substring(6);

        // Vector which will contain all the ranges which are successfully
        // parsed.
        ArrayList result = new ArrayList();
        StringTokenizer commaTokenizer = new StringTokenizer(rangeHeader, ",");

        // Parsing the range list
        while (commaTokenizer.hasMoreTokens()) {
            String rangeDefinition = commaTokenizer.nextToken().trim();

            Range currentRange = new Range();
            currentRange.length = fileLength;

            int dashPos = rangeDefinition.indexOf('-');

            if (dashPos == -1) {
                response.addHeader("Content-Range", "bytes */" + fileLength);
                response.sendError
                    (HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                return null;
            }

            if (dashPos == 0) {

                try {
                    long offset = Long.parseLong(rangeDefinition);
                    currentRange.start = fileLength + offset;
                    currentRange.end = fileLength - 1;
                } catch (NumberFormatException e) {
                    response.addHeader("Content-Range",
                                       "bytes */" + fileLength);
                    response.sendError
                        (HttpServletResponse
                         .SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    return null;
                }

            } else {

                try {
                    currentRange.start = Long.parseLong
                        (rangeDefinition.substring(0, dashPos));
                    if (dashPos < rangeDefinition.length() - 1)
                        currentRange.end = Long.parseLong
                            (rangeDefinition.substring
                             (dashPos + 1, rangeDefinition.length()));
                    else
                        currentRange.end = fileLength - 1;
                } catch (NumberFormatException e) {
                    response.addHeader("Content-Range",
                                       "bytes */" + fileLength);
                    response.sendError
                        (HttpServletResponse
                         .SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    return null;
                }

            }

            if (!currentRange.validate()) {
                response.addHeader("Content-Range", "bytes */" + fileLength);
                response.sendError
                    (HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                return null;
            }

            result.add(currentRange);
        }

        return result;
    }


    // -------------------------------------------------------- protected Methods

    
    /**
     * Check if the if-match condition is satisfied.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     * @param resourceInfo File object
     * @return boolean true if the resource meets the specified condition,
     * and false if the condition is not satisfied, in which case request
     * processing is stopped
     */
    protected boolean checkIfMatch(HttpServletRequest request,
                                 HttpServletResponse response,
                                 File resourceAttributes)
        throws IOException {

        String eTag = getETag(resourceAttributes);
        String headerValue = request.getHeader("If-Match");
        if (headerValue != null) {
            if (headerValue.indexOf('*') == -1) {

                StringTokenizer commaTokenizer = new StringTokenizer
                    (headerValue, ",");
                boolean conditionSatisfied = false;

                while (!conditionSatisfied && commaTokenizer.hasMoreTokens()) {
                    String currentToken = commaTokenizer.nextToken();
                    if (currentToken.trim().equals(eTag))
                        conditionSatisfied = true;
                }

                // If none of the given ETags match, 412 Precodition failed is
                // sent back
                if (!conditionSatisfied) {
                    response.sendError
                        (HttpServletResponse.SC_PRECONDITION_FAILED);
                    return false;
                }

            }
        }
        return true;

    }


    /**
     * Check if the if-modified-since condition is satisfied.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     * @param resourceInfo File object
     * @return boolean true if the resource meets the specified condition,
     * and false if the condition is not satisfied, in which case request
     * processing is stopped
     */
    protected boolean checkIfModifiedSince(HttpServletRequest request,
                                         HttpServletResponse response,
                                         File resourceAttributes)
        throws IOException {
        try {
            long headerValue = request.getDateHeader("If-Modified-Since");
            long lastModified = resourceAttributes.lastModified();
            if (headerValue != -1) {

                // If an If-None-Match header has been specified, if modified since
                // is ignored.
                if ((request.getHeader("If-None-Match") == null)
                    && (lastModified <= headerValue + 1000)) {
                    // The entity has not been modified since the date
                    // specified by the client. This is not an error case.
                    response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                    return false;
                }
            }
        } catch(IllegalArgumentException illegalArgument) {
            return true;
        }
        return true;

    }


    /**
     * Check if the if-none-match condition is satisfied.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     * @param resourceInfo File object
     * @return boolean true if the resource meets the specified condition,
     * and false if the condition is not satisfied, in which case request
     * processing is stopped
     */
    protected boolean checkIfNoneMatch(HttpServletRequest request,
                                     HttpServletResponse response,
                                     File resourceAttributes)
        throws IOException {

        String eTag = getETag(resourceAttributes);
        String headerValue = request.getHeader("If-None-Match");
        if (headerValue != null) {

            boolean conditionSatisfied = false;

            if (!headerValue.equals("*")) {

                StringTokenizer commaTokenizer =
                    new StringTokenizer(headerValue, ",");

                while (!conditionSatisfied && commaTokenizer.hasMoreTokens()) {
                    String currentToken = commaTokenizer.nextToken();
                    if (currentToken.trim().equals(eTag))
                        conditionSatisfied = true;
                }

            } else {
                conditionSatisfied = true;
            }

            if (conditionSatisfied) {

                // For GET and HEAD, we should respond with
                // 304 Not Modified.
                // For every other method, 412 Precondition Failed is sent
                // back.
                if ( ("GET".equals(request.getMethod()))
                     || ("HEAD".equals(request.getMethod())) ) {
                    response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                    return false;
                } else {
                    response.sendError
                        (HttpServletResponse.SC_PRECONDITION_FAILED);
                    return false;
                }
            }
        }
        return true;

    }


    /**
     * Check if the if-unmodified-since condition is satisfied.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     * @param resourceInfo File object
     * @return boolean true if the resource meets the specified condition,
     * and false if the condition is not satisfied, in which case request
     * processing is stopped
     */
    protected boolean checkIfUnmodifiedSince(HttpServletRequest request,
                                           HttpServletResponse response,
                                           File resourceAttributes)
        throws IOException {
        try {
            long lastModified = resourceAttributes.lastModified();
            long headerValue = request.getDateHeader("If-Unmodified-Since");
            if (headerValue != -1) {
                if ( lastModified > (headerValue + 1000)) {
                    // The entity has not been modified since the date
                    // specified by the client. This is not an error case.
                    response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
                    return false;
                }
            }
        } catch(IllegalArgumentException illegalArgument) {
            return true;
        }
        return true;

    }

    protected void copy(File f, InputStream is,
            OutputStream ostream)
        throws IOException {
        copy((is==null) ? new FileInputStream(f) : is, ostream);
    }

    
    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param resourceInfo The resource information
     * @param ostream The output stream to write to
     *
     * @exception IOException if an input/output error occurs
     */
    protected void copy(InputStream is, OutputStream ostream)
            throws IOException {

        IOException exception = null;
        InputStream resourceInputStream = null;

        resourceInputStream = is;

        InputStream istream = new BufferedInputStream
            (resourceInputStream, input);

        // Copy the input stream to the output stream
        exception = copyRange(istream, ostream);

        // Clean up the input stream
        try {
            istream.close();
        } catch (Throwable t) {
            ;
        }

        // Rethrow any exception that has occurred
        if (exception != null)
            throw exception;

    }


    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param resourceInfo The resource info
     * @param writer The writer to write to
     *
     * @exception IOException if an input/output error occurs
     */
    protected void copy(File cacheEntry, InputStream is, PrintWriter writer)
        throws IOException {

        IOException exception = null;

        InputStream resourceInputStream = (is == null) ? 
                new FileInputStream(cacheEntry) : is;

        Reader reader;
        if (fileEncoding == null) {
            reader = new InputStreamReader(resourceInputStream);
        } else {
            reader = new InputStreamReader(resourceInputStream,
                                           fileEncoding);
        }

        // Copy the input stream to the output stream
        exception = copyRange(reader, writer);

        // Clean up the reader
        try {
            reader.close();
        } catch (Throwable t) {
            ;
        }

        // Rethrow any exception that has occurred
        if (exception != null)
            throw exception;

    }


    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param resourceInfo The ResourceInfo object
     * @param ostream The output stream to write to
     * @param range Range the client wanted to retrieve
     * @exception IOException if an input/output error occurs
     */
    protected void copy(File cacheEntry, ServletOutputStream ostream,
                      Range range)
        throws IOException {

        IOException exception = null;

        InputStream resourceInputStream = new FileInputStream(cacheEntry);
        InputStream istream =
            new BufferedInputStream(resourceInputStream, input);
        exception = copyRange(istream, ostream, range.start, range.end);

        // Clean up the input stream
        try {
            istream.close();
        } catch (Throwable t) {
            ;
        }

        // Rethrow any exception that has occurred
        if (exception != null)
            throw exception;

    }


    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param resourceInfo The ResourceInfo object
     * @param writer The writer to write to
     * @param range Range the client wanted to retrieve
     * @exception IOException if an input/output error occurs
     */
    protected void copy(File cacheEntry, PrintWriter writer,
                      Range range)
        throws IOException {

        IOException exception = null;

        InputStream resourceInputStream = new FileInputStream(cacheEntry);

        Reader reader;
        if (fileEncoding == null) {
            reader = new InputStreamReader(resourceInputStream);
        } else {
            reader = new InputStreamReader(resourceInputStream,
                                           fileEncoding);
        }

        exception = copyRange(reader, writer, range.start, range.end);

        // Clean up the input stream
        try {
            reader.close();
        } catch (Throwable t) {
            ;
        }

        // Rethrow any exception that has occurred
        if (exception != null)
            throw exception;

    }


    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param resourceInfo The ResourceInfo object
     * @param ostream The output stream to write to
     * @param ranges Enumeration of the ranges the client wanted to retrieve
     * @param contentType Content type of the resource
     * @exception IOException if an input/output error occurs
     */
    protected void copy(File cacheEntry, ServletOutputStream ostream,
                      Iterator ranges, String contentType)
        throws IOException {

        IOException exception = null;

        while ( (exception == null) && (ranges.hasNext()) ) {

            InputStream resourceInputStream = new FileInputStream(cacheEntry);
            InputStream istream =
                new BufferedInputStream(resourceInputStream, input);

            Range currentRange = (Range) ranges.next();

            // Writing MIME header.
            ostream.println();
            ostream.println("--" + mimeSeparation);
            if (contentType != null)
                ostream.println("Content-Type: " + contentType);
            ostream.println("Content-Range: bytes " + currentRange.start
                           + "-" + currentRange.end + "/"
                           + currentRange.length);
            ostream.println();

            // Printing content
            exception = copyRange(istream, ostream, currentRange.start,
                                  currentRange.end);

            try {
                istream.close();
            } catch (Throwable t) {
                ;
            }

        }

        ostream.println();
        ostream.print("--" + mimeSeparation + "--");

        // Rethrow any exception that has occurred
        if (exception != null)
            throw exception;

    }


    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param resourceInfo The ResourceInfo object
     * @param writer The writer to write to
     * @param ranges Enumeration of the ranges the client wanted to retrieve
     * @param contentType Content type of the resource
     * @exception IOException if an input/output error occurs
     */
    protected void copy(File cacheEntry, PrintWriter writer,
                      Iterator ranges, String contentType)
        throws IOException {

        IOException exception = null;

        while ( (exception == null) && (ranges.hasNext()) ) {

            InputStream resourceInputStream = new FileInputStream(cacheEntry);
            
            Reader reader;
            if (fileEncoding == null) {
                reader = new InputStreamReader(resourceInputStream);
            } else {
                reader = new InputStreamReader(resourceInputStream,
                                               fileEncoding);
            }

            Range currentRange = (Range) ranges.next();

            // Writing MIME header.
            writer.println();
            writer.println("--" + mimeSeparation);
            if (contentType != null)
                writer.println("Content-Type: " + contentType);
            writer.println("Content-Range: bytes " + currentRange.start
                           + "-" + currentRange.end + "/"
                           + currentRange.length);
            writer.println();

            // Printing content
            exception = copyRange(reader, writer, currentRange.start,
                                  currentRange.end);

            try {
                reader.close();
            } catch (Throwable t) {
                ;
            }

        }

        writer.println();
        writer.print("--" + mimeSeparation + "--");

        // Rethrow any exception that has occurred
        if (exception != null)
            throw exception;

    }


    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param istream The input stream to read from
     * @param ostream The output stream to write to
     * @return Exception which occurred during processing
     */
    protected IOException copyRange(InputStream istream,
                                  OutputStream ostream) {

        // Copy the input stream to the output stream
        IOException exception = null;
        byte buffer[] = new byte[input];
        int len = buffer.length;
        while (true) {
            try {
                len = istream.read(buffer);
                if (len == -1)
                    break;
                ostream.write(buffer, 0, len);
            } catch (IOException e) {
                exception = e;
                len = -1;
                break;
            }
        }
        return exception;

    }


    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param reader The reader to read from
     * @param writer The writer to write to
     * @return Exception which occurred during processing
     */
    protected IOException copyRange(Reader reader, PrintWriter writer) {

        // Copy the input stream to the output stream
        IOException exception = null;
        char buffer[] = new char[input];
        int len = buffer.length;
        while (true) {
            try {
                len = reader.read(buffer);
                if (len == -1)
                    break;
                writer.write(buffer, 0, len);
            } catch (IOException e) {
                exception = e;
                len = -1;
                break;
            }
        }
        return exception;

    }


    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param istream The input stream to read from
     * @param ostream The output stream to write to
     * @param start Start of the range which will be copied
     * @param end End of the range which will be copied
     * @return Exception which occurred during processing
     */
    protected IOException copyRange(InputStream istream,
                                  ServletOutputStream ostream,
                                  long start, long end) {

        try {
            istream.skip(start);
        } catch (IOException e) {
            return e;
        }

        IOException exception = null;
        long bytesToRead = end - start + 1;

        byte buffer[] = new byte[input];
        int len = buffer.length;
        while ( (bytesToRead > 0) && (len >= buffer.length)) {
            try {
                len = istream.read(buffer);
                if (bytesToRead >= len) {
                    ostream.write(buffer, 0, len);
                    bytesToRead -= len;
                } else {
                    ostream.write(buffer, 0, (int) bytesToRead);
                    bytesToRead = 0;
                }
            } catch (IOException e) {
                exception = e;
                len = -1;
            }
            if (len < buffer.length)
                break;
        }

        return exception;

    }


    /**
     * Copy the contents of the specified input stream to the specified
     * output stream, and ensure that both streams are closed before returning
     * (even in the face of an exception).
     *
     * @param reader The reader to read from
     * @param writer The writer to write to
     * @param start Start of the range which will be copied
     * @param end End of the range which will be copied
     * @return Exception which occurred during processing
     */
    protected IOException copyRange(Reader reader, PrintWriter writer,
                                  long start, long end) {

        try {
            reader.skip(start);
        } catch (IOException e) {
            return e;
        }

        IOException exception = null;
        long bytesToRead = end - start + 1;

        char buffer[] = new char[input];
        int len = buffer.length;
        while ( (bytesToRead > 0) && (len >= buffer.length)) {
            try {
                len = reader.read(buffer);
                if (bytesToRead >= len) {
                    writer.write(buffer, 0, len);
                    bytesToRead -= len;
                } else {
                    writer.write(buffer, 0, (int) bytesToRead);
                    bytesToRead = 0;
                }
            } catch (IOException e) {
                exception = e;
                len = -1;
            }
            if (len < buffer.length)
                break;
        }

        return exception;

    }



    // ------------------------------------------------------ Range Inner Class


    protected class Range {

        public long start;
        public long end;
        public long length;

        /**
         * Validate range.
         */
        public boolean validate() {
            if (end >= length)
                end = length - 1;
            return ( (start >= 0) && (end >= 0) && (start <= end)
                     && (length > 0) );
        }

        public void recycle() {
            start = 0;
            end = 0;
            length = 0;
        }

    }

    
    // ----------------- Directory rendering --------------------
    
    // Just basic HTML rendering - extend or replace for xslt

    private void renderDir(HttpServletRequest request, 
            HttpServletResponse response, 
            File resFile,
            boolean content) throws IOException {
        String contentType = "text/html;charset=UTF-8";

        ServletOutputStream ostream = null;
        PrintWriter writer = null;

        if (content) {
            // Trying to retrieve the servlet output stream
            try {
                ostream = response.getOutputStream();
            } catch (IllegalStateException e) {
                // If it fails, we try to get a Writer instead if we're
                // trying to serve a text file
                if ( (contentType == null)
                     || (contentType.startsWith("text")) ) {
                    writer = response.getWriter();
                } else {
                    throw e;
                }
            }

        }

        // Set the appropriate output headers
        response.setContentType(contentType);
        
        InputStream renderResult = null;

        if (content) {
            // Serve the directory browser
            renderResult =
                render(request.getContextPath(), resFile);
        }


        // Copy the input stream to our output stream (if requested)
        if (content) {
            try {
                response.setBufferSize(output);
            } catch (IllegalStateException e) {
                // Silent catch
            }
            if (ostream != null) {
                copy(renderResult, ostream);
            } else {
                copy(null, renderResult, writer);
            }
        }
            
    }

    /**
     *  Decide which way to render. HTML or XML.
     */
    protected InputStream render(String contextPath, File cacheEntry) {
        return renderHtml(contextPath, cacheEntry);
    }


    /**
     * Return an InputStream to an HTML representation of the contents
     * of this directory.
     *
     * @param contextPath Context path to which our internal paths are
     *  relative
     */
    protected InputStream renderHtml(String contextPath, File cacheEntry) {

        String name = cacheEntry.getName();

        // Number of characters to trim from the beginnings of filenames
        int trim = name.length();
        if (!name.endsWith("/"))
            trim += 1;
        if (name.equals("/"))
            trim = 1;

        // Prepare a writer to a buffered area
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        OutputStreamWriter osWriter = null;
        try {
            osWriter = new OutputStreamWriter(stream, "UTF8");
        } catch (Exception e) {
            // Should never happen
            osWriter = new OutputStreamWriter(stream);
        }
        PrintWriter writer = new PrintWriter(osWriter);

        StringBuffer sb = new StringBuffer();
        
        // rewriteUrl(contextPath) is expensive. cache result for later reuse
        String rewrittenContextPath =  rewriteUrl(contextPath);

        // Render the page header
        sb.append("<html>\r\n");
        sb.append("<head>\r\n");
        sb.append("<title>");
        sb.append(sm.getString("directory.title", name));
        sb.append("</title>\r\n");
        sb.append("<STYLE><!--");
        sb.append(org.apache.catalina.util.TomcatCSS.TOMCAT_CSS);
        sb.append("--></STYLE> ");
        sb.append("</head>\r\n");
        sb.append("<body>");
        sb.append("<h1>");
        sb.append(sm.getString("directory.title", name));

        // Render the link to our parent (if required)
        String parentDirectory = name;
        if (parentDirectory.endsWith("/")) {
            parentDirectory =
                parentDirectory.substring(0, parentDirectory.length() - 1);
        }
        int slash = parentDirectory.lastIndexOf('/');
        if (slash >= 0) {
            String parent = name.substring(0, slash);
            sb.append(" - <a href=\"");
            sb.append(rewrittenContextPath);
            if (parent.equals(""))
                parent = "/";
            sb.append(rewriteUrl(parent));
            if (!parent.endsWith("/"))
                sb.append("/");
            sb.append("\">");
            sb.append("<b>");
            sb.append(sm.getString("directory.parent", parent));
            sb.append("</b>");
            sb.append("</a>");
        }

        sb.append("</h1>");
        sb.append("<HR size=\"1\" noshade=\"noshade\">");

        sb.append("<table width=\"100%\" cellspacing=\"0\"" +
                     " cellpadding=\"5\" align=\"center\">\r\n");

        // Render the column headings
        sb.append("<tr>\r\n");
        sb.append("<td align=\"left\"><font size=\"+1\"><strong>");
        sb.append(sm.getString("directory.filename"));
        sb.append("</strong></font></td>\r\n");
        sb.append("<td align=\"center\"><font size=\"+1\"><strong>");
        sb.append(sm.getString("directory.size"));
        sb.append("</strong></font></td>\r\n");
        sb.append("<td align=\"right\"><font size=\"+1\"><strong>");
        sb.append(sm.getString("directory.lastModified"));
        sb.append("</strong></font></td>\r\n");
        sb.append("</tr>");


        // Render the directory entries within this directory
        String[] files = cacheEntry.list();
        boolean shade = false;
        for (int i=0; i<files.length; i++) {

            String resourceName = files[i];
            String trimmed = resourceName/*.substring(trim)*/;
            if (trimmed.equalsIgnoreCase("WEB-INF") ||
                    trimmed.equalsIgnoreCase("META-INF"))
                continue;

            File childCacheEntry = new File(cacheEntry, resourceName);

            sb.append("<tr");
            if (shade)
                sb.append(" bgcolor=\"#eeeeee\"");
            sb.append(">\r\n");
            shade = !shade;

            sb.append("<td align=\"left\">&nbsp;&nbsp;\r\n");
            sb.append("<a href=\"");
            sb.append(rewrittenContextPath);
            resourceName = rewriteUrl(name + resourceName);
            sb.append(resourceName);
            boolean isDir = childCacheEntry.isDirectory(); 
            if (isDir)
                sb.append("/");
            sb.append("\"><tt>");
            sb.append(trimmed);
            if (isDir)
                sb.append("/");
            sb.append("</tt></a></td>\r\n");

            sb.append("<td align=\"right\"><tt>");
            if (isDir)
                sb.append("&nbsp;");
            else
                displaySize(sb,childCacheEntry.length());
            sb.append("</tt></td>\r\n");

            sb.append("<td align=\"right\"><tt>");
            sb.append(lastModifiedHttp(childCacheEntry));
            sb.append("</tt></td>\r\n");

            sb.append("</tr>\r\n");
        }


        // Render the page footer
        sb.append("</table>\r\n");

        sb.append("<HR size=\"1\" noshade=\"noshade\">");

        String readme = getReadme(cacheEntry);
        if (readme!=null) {
            sb.append(readme);
            sb.append("<HR size=\"1\" noshade=\"noshade\">");
        }

        sb.append("<h3>").append(ServerInfo.getServerInfo()).append("</h3>");
        sb.append("</body>\r\n");
        sb.append("</html>\r\n");

        // Return an input stream to the underlying bytes
        writer.write(sb.toString());
        writer.flush();
        return (new ByteArrayInputStream(stream.toByteArray()));

    }

    /**
     * Display the size of a file.
     */
    protected void displaySize(StringBuffer buf, long filesize) {

        long leftside = filesize / 1024;
        long rightside = (filesize % 1024) / 103;  // makes 1 digit
        if (leftside == 0 && rightside == 0 && filesize != 0)
            rightside = 1;
        buf.append(leftside).append(".").append(rightside);
        buf.append(" KB");
    }

    /**
     * Get the readme file as a string.
     */
    protected String getReadme(File directory) {
        if (readmeFile!=null) {
            try {
                File rf = new File(directory, readmeFile);

                if (rf.exists()) {
                    StringWriter buffer = new StringWriter();
                    InputStream is = new FileInputStream(rf);
                    copyRange(new InputStreamReader(is),
                              new PrintWriter(buffer));

                    return buffer.toString();
                 }
             } catch(Throwable e) {
                 ; /* Should only be IOException or NamingException
                    * can be ignored
                    */
             }
        }
        return null;
    }
    
}
