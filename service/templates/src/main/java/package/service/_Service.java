package <%=packageName%>.service;

<% if (useInterface == false) { %>import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class <%= serviceClass %>Service {

    private final Logger log = LoggerFactory.getLogger(<%= serviceClass %>Service.class);

}<% } else  { %>public interface <%= serviceClass %>Service {

}<% } %>
