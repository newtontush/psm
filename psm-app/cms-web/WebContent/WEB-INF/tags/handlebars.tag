<%@ tag import="java.util.*" %>
<%@ tag import="com.github.jknack.handlebars.Context" %>
<%@ tag import="com.github.jknack.handlebars.Handlebars" %>
<%@ tag import="com.github.jknack.handlebars.io.ServletContextTemplateLoader" %>
<%@ tag import="com.github.jknack.handlebars.io.TemplateLoader" %>
<%@ tag import="com.github.jknack.handlebars.springmvc.HandlebarsViewResolver" %>
<%@ tag import="com.github.jknack.handlebars.Template" %>
<%@ attribute name="template" required="true" %>
<%@ attribute name="context" required="true" type="javax.servlet.jsp.PageContext" %>
<%
JspContext jspContext = getJspContext();

try {
  HandlebarsViewResolver viewResolver =
    (HandlebarsViewResolver) jspContext.getAttribute(
      "HandlebarsViewResolver",
      PageContext.REQUEST_SCOPE
  );
  if (viewResolver == null) {
    %>could not load handlebars view resolver<%
  }
  Handlebars handlebars = viewResolver.getHandlebars();
  Template t = handlebars.compile(jspContext.getAttribute("template").toString());
  Map map = new HashMap();

  // parent page context, passed via attr
  PageContext parentContext = (PageContext) jspContext.getAttribute("context");

  int[] scopes = {
    PageContext.APPLICATION_SCOPE,
    PageContext.SESSION_SCOPE,
    PageContext.REQUEST_SCOPE,
    PageContext.PAGE_SCOPE,
  };
  for (int scope : scopes) {
    Enumeration attrs = parentContext.getAttributeNamesInScope(scope);

    while(attrs.hasMoreElements()) {
        String name = attrs.nextElement().toString();

        // replicate attrs into current context too
        map.put(name, parentContext.getAttribute(name, scope));
    }
  }
  Context context = Context.newBuilder(map).build();

  %><%= t.apply(context) %><%

  context.destroy();
} catch (Exception e) {
  %><%= e %><%
}
%>
