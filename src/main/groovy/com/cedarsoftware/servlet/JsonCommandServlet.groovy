package com.cedarsoftware.servlet

import com.cedarsoftware.util.IOUtilities
import com.cedarsoftware.util.io.JsonWriter
import groovy.transform.CompileStatic
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.security.AccessControlException
import java.util.regex.Matcher

/**
 * This class will accept JSON REST requests, find the named Spring Bean,
 * find the method, and then invoke the method.  Once complete, it will
 * convert the return object to JSON and then send that back to the client.
 * The requests are typically made from a Javascript client, although they
 * could easily be made from Python, Java, or Objective C.  The request
 * comes in with http://yoursite.com/json/controllerName/methodName.  The
 * "json" part can be whatever name you map to this servlet in web.xml.
 * The arguments are sent as the HTTP POST body, or they can be sent via
 * query params like this:
 *
 *     http://yoursite.com/json/Controller/methodName?json=[arg1, arg2,...]
 *
 * When calling the JsonServlet, it will always return an object in the form:
 *
 *     {"data":v,"status":false|true|null}
 *
 * where the value 'v' is the return value of the Controller method called.
 * The status is 'true' if the method call properly succeeded.  Use the
 * return value 'v' when status === true.  If status === false then the
 * communications reach the server, however, there was an exception.  A Controller
 * method threw an exception, invalid JSON was passed in, the name of the
 * controller targeted was wrong, the controller targeted was not a BaseController,
 * or the method on the controller was not found.  The value 'v' when the the
 * status === false will indicate the error.  If the status === null then the
 * call() method within the browser never reached the server.  The value 'v' is
 * a message indicating a network communications issue.
 *
 * The returned JSON is gzip compressed if the caller indicates that it
 * accepts Content-Encoding of gzip AND the return message is greater than
 * 512 bytes. The return stream from methods that return large arrays and/or
 * object graphs compresses especially well.
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br/>
 *         Copyright (c) Cedar Software LLC
 *         <br/><br/>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br/><br/>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br/><br/>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
@CompileStatic
public class JsonCommandServlet extends HttpServlet
{
    public static final ThreadLocal<HttpServletRequest> servletRequest = new ThreadLocal<>()
    public static final ThreadLocal<HttpServletResponse> servletResponse = new ThreadLocal<>()
    public static final String ATTRIBUTE_STATUS = "status"
    public static final String ATTRIBUTE_FAIL_MESSAGE = "failMsg"
    private SpringConfigurationProvider springCfgProvider
    private NCubeConfigurationProvider nCubeCfgProvider
    private static final Logger LOG = LogManager.getLogger(JsonCommandServlet.class)

    public void init()
    {
        try
        {
            nCubeCfgProvider = new NCubeConfigurationProvider(getServletConfig())
            springCfgProvider = new SpringConfigurationProvider(getServletConfig())
        }
        catch (Exception e)
        {
            LOG.error("Error initializing app context", e)
        }
    }

    /**
     * Handle JSON GET style request.  In this case, 'controller', 'method', and 'json' are
     * specified as URL parameters.
     * Example: http://cedarsoftware.com/coolApp/CoolController/coolMethod?json=[args...]
     */
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
    {
        try
        {
            request.setAttribute(ATTRIBUTE_STATUS, true)  // start with status of true
            servletRequest.set(request)       // store on ThreadLocal
            servletResponse.set(response)     // store on ThreadLocal

            // Step 1: Ensure that the request header has Content-Length correctly specified.
            String json = request.getParameter("json")

            if (json == null || json.trim().length() < 1)
            {
                sendJsonResponse(request, response, new Envelope("error: HTTP-GET had empty or no 'json' parameter.", false))
                return
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("GET RESTful JSON")
            }

            handleRequestAndResponse(request, response, json)
        }
        finally
        {
            removeThreadLocals()
        }
    }

    /**
     * Process JSON POST style where the controller, method, and arguments are passed in as the
     * POST data.
     * Example: http://cedarsoftware.com/coolApp/CoolController/coolMethod
     * Post body contains the arguments in JSON format.
     */
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
    {
        request.setAttribute(ATTRIBUTE_STATUS, true)   // start with status of true
        servletRequest.set(request)        // store on ThreadLocal
        servletResponse.set(response)      // store on ThreadLocal

        try
        {
            // Ensure that the request header has Content-Length correctly specified.
            if (request.contentLength < 1)
            {
                sendJsonResponse(request, response, new Envelope("error: Call to server had incorrect Content-Length specified.", false))
                return
            }

            // Transfer request body to byte[]
            byte[] jsonBytes = new byte[request.contentLength]
            IOUtilities.transfer(request.inputStream, jsonBytes)
            String json = new String(jsonBytes, "UTF-8")

            if (LOG.isDebugEnabled())
            {
                LOG.debug("POST RESTful JSON")
            }
            handleRequestAndResponse(request, response, json)
        }
        catch (Exception e)
        {
            sendJsonResponse(request, response, new Envelope("error: Unable to read HTTP-POST JSON content.", false))
        }
        finally
        {
            removeThreadLocals()
        }
    }

    /**
     * Remove ThreadLocals containing HttpServletRequest and response so that when thread is returned
     * to threadpool there are no lingering references to these values.
     */
    private static void removeThreadLocals()
    {
        servletRequest.remove()
        servletResponse.remove()
    }

    /**
     * @param request HttpServletRequest passed to the command servlet.
     * @return Either a JsonCommandServlet ConfigurationProvider or an Envelope instance,
     * if unable to obtain the configuration provider.  The URL must contain /controller/method,
     * and then if the controller name can be found in the n-cube provider, then the ncubeCfgProvider
     * is returned, otherwise the springCfgProvider is checked.  If the controller name can be
     * found there, then the springCfgProvider is returned.  If the controller cannot be located
     * by name from either provider, and Envelope is returned, indicating this error.
     */
    private Object getProvider(HttpServletRequest request, String json)
    {
        Object var = ConfigurationProvider.getUrlMatcher(request, json)
        if (var instanceof Envelope)
        {   // Bad controller name or missing method, etc. within the URL String
            return var
        }

        final String controllerName = ((Matcher) var).group(1)

        var = nCubeCfgProvider.getController(controllerName)
        if (var instanceof Envelope)
        {
            var = springCfgProvider.getController(controllerName)
            return var instanceof Envelope ? var : springCfgProvider
        }
        else
        {
            return nCubeCfgProvider
        }
    }

    /**
     * This is the main driver of the command servlet.  This code obtains the appropriate provider,
     * calls the appropriate method on the provider, and then optionally writes the return response,
     * if the called method did not write to the HTTP response.
     * @param request HttpServletRequest passed to the command servlet.
     * @param response HttpServletResponse passed to the command servlet.
     * @param json String arguments that were passed in JSON format.
     */
    private void handleRequestAndResponse(HttpServletRequest request, HttpServletResponse response, String json)
    {
        Object envelope
        try
        {
            Object provider = getProvider(request, json)
            if (provider instanceof Envelope)
            {
                envelope = provider
            }
            else
            {
                ConfigurationProvider cfgProvider = (ConfigurationProvider) provider
                envelope = cfgProvider.callController(request, json)
            }
        }
        catch (ThreadDeath d)
        {
            throw d
        }
        catch (Throwable e)
        {
            // Handle response in case of unhandled exception by controller
            Throwable t = getDeepestException(e)
            String msg = t.getClass().getName()
            if (t.message != null)
            {
                msg += ' ' + t.message
            }

            if (t instanceof IOException)
            {
                if ("org.apache.catalina.connector.ClientAbortException".equals(t.getClass().getName()))
                {
                    LOG.info("Client aborted connection while processing JSON request.")
                }
                else
                {
                    sendJsonResponse(request, response, new Envelope("error: Invalid JSON request made.", false))
                }
            }
            else if (t instanceof AccessControlException)
            {
                sendJsonResponse(request, response, new Envelope("error: Your session with our website appears to have ended.  Please log out and back in.", false))
            }
            else
            {
                sendJsonResponse(request, response, new Envelope("error: Communications issue between your computer and our website (" + msg + ')', false))
            }
            return
        }

        // Handle response (if the method did not)
        if (!response.isCommitted())
        {
	        // Send JSON result
	        long start = System.nanoTime()
	        sendJsonResponse(request, response, (Envelope) envelope)
	        long end = System.nanoTime()

	        if (end - start > 2000000000)
	        {    // Total time more than 2 seconds
	            if (json.length() > 256)
	            {
	                json = json.substring(0, 255)
	            }
	            LOG.info("Slow return response: " + json + " took " + ((end - start) / 1000000) + " ms")
	        }
        }
    }

    /**
     * Build and send the response Envelope to the client.
     * @param request original servlet request
     * @param response original servlet response
     * @param envelope data and status to be written
     */
    private static void sendJsonResponse(HttpServletRequest request, HttpServletResponse response, Envelope envelope)
    {
        try
        {
            if (response.isCommitted())
            {   // Cannot write, response has already been committed.
                return
            }
            String json = buildResponse(request, response, envelope)
            writeResponse(request, response, json)
        }
        catch (ThreadDeath t)
        {
            throw t
        }
        catch (Throwable t)
        {
            t = getDeepestException(t)
            String msg = t.getClass().getName()
            if (t.getMessage() != null)
            {
                msg += ' ' + t.getMessage()
            }

            if (t instanceof IOException)
            {
                if ("org.apache.catalina.connector.ClientAbortException".equals(t.getClass().getName()))
                {
                    LOG.info("Client aborted connection while processing JSON request.")
                }
                else
                {
                    LOG.warn("IOException - sending response: " + msg)
                }
            }
            else if (t instanceof AccessControlException)
            {
                LOG.warn("AccessControlException - sending response: " + msg)
            }
            else
            {
                LOG.warn("An unexpected exception occurred sending JSON response to client", t)
            }
        }
    }

    /**
     * Write the response Envelope to the client
     * @param request original servlet request
     * @param response original servlet response
     * @param json String response to write
     * @throws IOException
     */
    private static void writeResponse(HttpServletRequest request, HttpServletResponse response, String json) throws IOException
    {
        ByteArrayOutputStream jsonBytes = new ByteArrayOutputStream()
        jsonBytes.write(json.getBytes("UTF-8"))

        // For debugging
        if (LOG.isDebugEnabled())
        {
            LOG.debug("  return " + new String(jsonBytes.toByteArray(), "UTF-8"))
        }

        //  Header can be null coming from other WebClients (such as .NET client)
        String header = request.getHeader("Accept-Encoding")
        if (jsonBytes.size() > 512 && header != null && header.contains("gzip"))
        {   // Only compress if the output is longer than 512 bytes.

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream(jsonBytes.size())
            IOUtilities.compressBytes(jsonBytes, compressedBytes)

            if (compressedBytes.size() < jsonBytes.size())
            {   // Only write compressed if it is smaller than original JSON String
                response.setHeader("Content-Encoding", "gzip")
                jsonBytes = compressedBytes
            }
        }

        response.contentLength = jsonBytes.size()
        OutputStream output = new BufferedOutputStream(response.outputStream)
        jsonBytes.writeTo(output)
        output.flush()
    }

    /**
     * Build the response envelope as a String to be returned to the client.
     * @param request original servlet request
     * @param response original servlet response
     * @param envelope data and status to be written
     * @return String (JSON format) of envelope to be return to client.
     */
    private static String buildResponse(HttpServletRequest request, HttpServletResponse response, Envelope envelope)
    {
        Boolean success = (Boolean) request.getAttribute(ATTRIBUTE_STATUS)
        if (!success)
        {   // If the called method forcefully set status to false, then overwrite the data with the
            // value from the ATTRIBUTE_FAIL_MESSAGE (which will contain the failure reason).
            envelope.data = request.getAttribute(ATTRIBUTE_FAIL_MESSAGE)
        }
        response.contentType = "application/json"
        response.setHeader("Cache-Control", "private, no-cache, no-store")

        // Temporarily wrap return type in Object[] to shrink the return type in JSON format
        String retJson = JsonWriter.objectToJson([envelope.data] as Object[])
        StringBuilder s = new StringBuilder("{\"data\":")

        // Now pull off the Object[] wrapper.
        if ("[]".equals(retJson))
        {
            s.append("null")
        }
        else
        {
            s.append(retJson.substring(1, retJson.length() - 1))
        }

        s.append(",\"status\":")
        if (!success)
        {   // Servlet handler (invoked method) can force the status to null
            s.append(false)
        }
        else
        {
            s.append(envelope.status)
        }
        s.append('}')
        return s.toString()
    }

    /**
     * Get the deepest (original cause) of the exception chain.
     * @param e Throwable exception that occurred.
     * @return Throwable original (causal) exception
     */
    static Throwable getDeepestException(Throwable e)
    {
        while (e.cause != null)
        {
            e = e.cause
        }

        if (!(e instanceof AccessControlException || e instanceof IOException))
        {
            LOG.warn("unexpected exception occurred: ", e)
        }
        else
        {
            String msg = e.getClass().getName()
            if (e.message != null)
            {
                msg = msg + ' ' + e.message
            }

            LOG.warn("exception occurred: " + msg)
        }

        return e
    }
}