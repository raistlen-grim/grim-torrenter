package com.grimtorrenter.app;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

public class TorrentUploadForm {

    @RestForm("file")
    public FileUpload file;
}
