package org.apache.hive.service.servlet;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hive.service.cli.operation.OperationManager;
import org.apache.hive.service.cli.operation.SQLOperationDisplay;
import org.apache.hive.service.cli.session.HiveSession;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.Version;
import org.codehaus.jackson.map.JsonSerializer;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;

import org.apache.hive.service.cli.session.SessionManager;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.map.module.SimpleModule;
import org.codehaus.jackson.util.VersionUtil;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;


public class QueriesRESTfulAPIServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Log LOG = LogFactory.getLog(QueriesRESTfulAPIServlet.class);

    private static final String API_V1 = "v1";
    private static final String REQ_QUERIES = "queries";
    private static final String REQ_SESSIONS = "sessions";
    private static final String REQ_ACTIVE = "active";
    private static final String REQ_HISTORICAL = "historical";


    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        /*
            Available endpoints are:
             - /v1/queries/active
             - /v1/queries/historical
             - /v1/sessions
        */

        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Path to the endpoint is missing");
            return;
        }


        String[] splits = pathInfo.split("/");
        if (splits.length < 3) { //expecting at least 2 parts in the path
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Expecting at least 2 parts in the path");
            return;
        }

        ServletContext ctx = getServletContext();
        SessionManager sessionManager =
                (SessionManager) ctx.getAttribute("hive.sm");
        OperationManager operationManager = sessionManager.getOperationManager();

        String apiVersion = splits[1];
        if (apiVersion.equals(API_V1)) {
            String reqType = splits[2];
            if (reqType.equals(REQ_QUERIES)) {
                if (splits.length != 4) {
                    sendError(response, HttpServletResponse.SC_NOT_FOUND, "Expecting 3 parts in the path: /v1/queries/active or /v1/queries/historical");
                    return;
                }
                String queriesType = splits[3];
                if (queriesType.equals(REQ_ACTIVE)) {
                    Collection<SQLOperationDisplay> operations = operationManager.getLiveSqlOperations();
                    LOG.info("Returning active SQL operations via the RESTful API");
                    sendAsJson(response, operations);
                } else if (queriesType.equals(REQ_HISTORICAL)) {
                    Collection<SQLOperationDisplay> operations = operationManager.getHistoricalSQLOperations();
                    LOG.info("Returning historical SQL operations via the RESTful API");
                    sendAsJson(response, operations);
                } else {
                    sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Unknown query type: " + queriesType);
                    return;
                }
            } else if (reqType.equals(REQ_SESSIONS)) {
                Collection<HiveSession> hiveSessions = sessionManager.getSessions();
                LOG.info("Returning active sessions via the RESTful API");
                sendAsJson(response, hiveSessions);
            } else { // unrecognized request
                sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown request type: " + reqType);
                return;
            }
        } else { // unrecognized API version
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "This server only handles API v1");
            return;
        }
    }

    private void sendError(HttpServletResponse response,
                           Integer errorCode,
                           String message) {
        response.setStatus(errorCode);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            response.getWriter().write("{\"message\" : " + message + "}");
        } catch (IOException e) {
            LOG.error("Caught an exception while writing an HTTP error status", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void sendAsJson(
            HttpServletResponse response,
            Object obj) {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("CustomSessionModule", new Version(1, 0, 0, null));
        module.addSerializer(HiveSession.class, new HiveSessionSerializer());
        mapper.registerModule(module);

        try {
            PrintWriter out = response.getWriter();
            String objectAsJson = mapper.writeValueAsString(obj);
            out.print(objectAsJson);
            out.flush();
            out.close();
        } catch (IOException e) {
            LOG.error("Caught an exception while writing an HTTP response", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private class HiveSessionSerializer extends JsonSerializer<HiveSession> {
        @Override
        public void serialize(
                HiveSession hiveSession,
                JsonGenerator jgen,
                SerializerProvider serializerProvider)
                throws IOException, JsonProcessingException {
            long currentTime = System.currentTimeMillis();

            jgen.writeStartObject();
            jgen.writeStringField("username", hiveSession.getUserName());
            jgen.writeStringField("ipAddress", hiveSession.getIpAddress());
            jgen.writeNumberField("operationCount", hiveSession.getOpenOperationCount());
            jgen.writeNumberField("activeTime", (currentTime - hiveSession.getCreationTime()) / 1000);
            jgen.writeNumberField("idleTime", (currentTime - hiveSession.getLastAccessTime()) / 1000);
            jgen.writeStringField("sessionId", hiveSession.getSessionHandle().getSessionId().toString());
            jgen.writeEndObject();
        }
    }
}
