-- Match the legacy manifest limits retained in package design Appendix A.7.
ALTER TABLE content_extension_package
  MODIFY version VARCHAR(120) NOT NULL,
  MODIFY name VARCHAR(300) NOT NULL;
