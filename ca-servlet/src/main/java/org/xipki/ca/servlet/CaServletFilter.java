/*
 *
 * Copyright (c) 2013 - 2019 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.servlet;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.audit.Audits;
import org.xipki.audit.Audits.AuditConf;
import org.xipki.ca.api.profile.CertprofileFactory;
import org.xipki.ca.api.profile.CertprofileFactoryRegister;
import org.xipki.ca.api.publisher.CertPublisherFactoryRegister;
import org.xipki.ca.certprofile.xijson.CertprofileFactoryImpl;
import org.xipki.ca.server.CaManagerImpl;
import org.xipki.ca.server.CaServerConf;
import org.xipki.ca.server.CaServerConf.RemoteMgmt;
import org.xipki.ca.server.publisher.OcspCertPublisherFactory;
import org.xipki.security.Securities;
import org.xipki.security.util.X509Util;
import org.xipki.util.CollectionUtil;
import org.xipki.util.FileOrBinary;
import org.xipki.util.HttpConstants;
import org.xipki.util.InvalidConfException;
import org.xipki.util.IoUtil;
import org.xipki.util.LogUtil;
import org.xipki.util.StringUtil;

/**
 * TODO.
 * @author Lijun Liao
 */
public class CaServletFilter implements Filter {

  private static final Logger LOG = LoggerFactory.getLogger(CaServletFilter.class);

  private static final String DFLT_CA_SERVER_CFG = "xipki/etc/ca/ca.json";

  private static final String DFLT_SYSLOG_AUDIT_CFG = "xipki/etc/ca/org.xipki.audit.syslog.cfg";

  private Securities securities;

  private CaManagerImpl caManager;

  private HealthCheckServlet healthServlet;

  private HttpCmpServlet cmpServlet;

  private HttpRestServlet restServlet;

  private HttpScepServlet scepServlet;

  private boolean remoteMgmtEnabled;

  private HttpMgmtServlet mgmtServlet;

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    CaServerConf conf;
    try {
      conf = CaServerConf.readConfFromFile(IoUtil.expandFilepath(DFLT_CA_SERVER_CFG));
    } catch (IOException | InvalidConfException ex) {
      throw new IllegalArgumentException(
          "could not parse CA configuration file " + DFLT_CA_SERVER_CFG, ex);
    }

    AuditConf audit = conf.getAudit();
    String auditType = audit.getType();
    if (StringUtil.isBlank(auditType)) {
      auditType = "embed";
    }

    String auditConf = audit.getConf();
    if ("syslog".equalsIgnoreCase(auditType) && auditConf == null) {
      auditConf = DFLT_SYSLOG_AUDIT_CFG;
    }

    Audits.init(auditType, auditConf);

    securities = new Securities();
    try {
      securities.init(conf.getSecurity());
    } catch (IOException | InvalidConfException ex) {
      throw new ServletException("could not initialize Securites", ex);
    }

    caManager = new CaManagerImpl();
    caManager.setSecurityFactory(securities.getSecurityFactory());

    // Certprofiles
    caManager.setCertprofileFactoryRegister(
        initCertprofileFactoryRegister(conf.getCertprofileFactories()));

    // Publisher
    CertPublisherFactoryRegister publiserFactoryRegister = new CertPublisherFactoryRegister();
    publiserFactoryRegister.registFactory(new OcspCertPublisherFactory());
    caManager.setCertPublisherFactoryRegister(publiserFactoryRegister);
    caManager.setCaServerConf(conf);

    caManager.startCaSystem();

    this.cmpServlet = new HttpCmpServlet();
    this.cmpServlet.setResponderManager(caManager);

    this.healthServlet = new HealthCheckServlet();
    this.healthServlet.setResponderManager(caManager);

    this.restServlet = new HttpRestServlet();
    this.restServlet.setResponderManager(caManager);

    this.scepServlet = new HttpScepServlet();
    this.scepServlet.setResponderManager(caManager);

    RemoteMgmt remoteMgmt = conf.getRemoteMgmt();
    this.remoteMgmtEnabled = remoteMgmt == null ? false : remoteMgmt.isEnabled();
    LOG.info("remote management is {}", remoteMgmtEnabled ? "enabled" : "disabled");

    if (this.remoteMgmtEnabled) {
      List<FileOrBinary> certFiles = remoteMgmt.getCerts();
      if (CollectionUtil.isEmpty(certFiles)) {
        LOG.error("no client certificate is configured, disable the remote managent");
      } else {
        Set<X509Certificate> certs = new HashSet<>();
        for (FileOrBinary m : certFiles) {
          try {
            X509Certificate cert = X509Util.parseCert(m.readContent());
            certs.add(cert);
          } catch (CertificateException | IOException ex) {
            String msg = "could not parse the client certificate";
            if (m.getFile() != null) {
              msg += " " + m.getFile();
            }
            LogUtil.error(LOG, ex, msg);
          }

        }

        if (certs.isEmpty()) {
          LOG.error("could not find any valid client certificates, disable the remote management");
        } else {
          mgmtServlet = new HttpMgmtServlet();
          mgmtServlet.setCaManager(caManager);
          mgmtServlet.setMgmtCerts(certs);
        }
      }
    }
  }

  @Override
  public void destroy() {
    if (securities != null) {
      securities.close();
    }

    if (caManager != null) {
      caManager.close();
    }
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest & response instanceof HttpServletResponse)) {
      throw new ServletException("Only HTTP request is supported");
    }

    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;

    String path = req.getServletPath();
    if (path.startsWith("/cmp/")) {
      req.setAttribute(HttpConstants.ATTR_XIPKI_PATH, path.substring(4)); // 4 = "/cmp".length()
      cmpServlet.service(req, res);
    } else if (path.startsWith("/rest/")) {
      req.setAttribute(HttpConstants.ATTR_XIPKI_PATH, path.substring(5)); // 5 = "/rest".length()
      restServlet.service(req, res);
    } else if (path.startsWith("/scep/")) {
      req.setAttribute(HttpConstants.ATTR_XIPKI_PATH, path.substring(5)); // 5 = "/scep".length()
      scepServlet.service(req, res);
    } else if (path.startsWith("/health/")) {
      req.setAttribute(HttpConstants.ATTR_XIPKI_PATH, path.substring(7)); // 7 = "/health".length()
      healthServlet.service(req, res);
    } else if (path.startsWith("/mgmt/")) {
      if (remoteMgmtEnabled) {
        req.setAttribute(HttpConstants.ATTR_XIPKI_PATH, path.substring(5)); // 5 = "/mgmt".length()
        mgmtServlet.service(req, res);
      } else {
        sendError(res, HttpServletResponse.SC_FORBIDDEN);
      }
    } else {
      sendError(res, HttpServletResponse.SC_NOT_FOUND);
    }
  }

  private static void sendError(HttpServletResponse res, int status) {
    res.setStatus(status);
    res.setContentLength(0);
  }

  private CertprofileFactoryRegister initCertprofileFactoryRegister(List<String> factories)
      throws ServletException {
    CertprofileFactoryRegister certprofileFactoryRegister = new CertprofileFactoryRegister();
    certprofileFactoryRegister.registFactory(new CertprofileFactoryImpl());

    // register additional CertprofileFactories
    if (factories != null) {
      for (String className : factories) {
        try {
          Class<?> clazz = Class.forName(className);
          CertprofileFactory factory = (CertprofileFactory) clazz.newInstance();
          certprofileFactoryRegister.registFactory(factory);
        } catch (ClassCastException | ClassNotFoundException | IllegalAccessException
            | InstantiationException ex) {
          LOG.error("error caught while initializing CertprofileFactory "
              + className + ": " + ex.getClass().getName() + ": " + ex.getMessage(), ex);
        }
      }
    }

    return certprofileFactoryRegister;
  }

}
