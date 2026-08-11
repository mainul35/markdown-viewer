module com.mdviewer {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;
    requires net.sourceforge.plantuml;

    opens com.mdviewer to javafx.fxml;
    exports com.mdviewer;
    exports com.mdviewer.service;
    exports com.mdviewer.ui;
}
