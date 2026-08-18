module com.mdviewer {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;
    requires net.sourceforge.plantuml;
    // ImageIO, for writing cropped images out to PNG.
    requires java.desktop;
    // JSObject, for letting the preview page call back into the app - the table cell
    // editor. netscape.javascript lives in its own module, not in javafx.web.
    requires jdk.jsobject;
    // HttpClient, for the assistant's streaming calls. In the JDK, so no dependency.
    requires java.net.http;
    // The loopback listener that receives the OAuth redirect at sign-in. Also in the JDK.
    requires jdk.httpserver;

    opens com.mdviewer to javafx.fxml;
    exports com.mdviewer;
    exports com.mdviewer.service;
    exports com.mdviewer.ui;
    exports com.mdviewer.ai;
}
