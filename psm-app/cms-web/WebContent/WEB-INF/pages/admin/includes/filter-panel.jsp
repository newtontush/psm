<%--
  The filter panel for the system admin login > User Accounts pages (Providers, Service Agents, etc.).
--%>
<%@ include file="/WEB-INF/pages/admin/includes/taglibs.jsp" %>
<c:set var="cssClz" value="filterPanel show" />
<c:if test="${!criteria.showFilterPanel}">
    <c:set var="cssClz" value="filterPanel hide" />
</c:if>
<form:form
  id="searchUserAccountsForm"
  cssClass="${cssClz}"
  action="${ctx}/system/user/search?role=${role}"
  modelAttribute="criteria"
  method="post"
>
    <div class="floatW">
        <div class="leftCol">
            <div class="row">
                <label for="filterPanelUsername">Username</label>
                <form:input id="filterPanelUsername" cssClass="normalInput" path="username" />
            </div>
            <div class="row">
                <label for="filterPanelEmail">Email</label>
                <form:input id="filterPanelEmail" cssClass="normalInput" path="email"/>
            </div>
        </div>
        <div class="rightCol">
            <div class="row">
                <label for="filterPanelLastName">Last Name</label>
                <form:input id="filterPanelLastName" cssClass="normalInput" path="lastName" />
            </div>
            <div class="row">
                <label for="filterPanelFirstName">First Name</label>
                <form:input id="filterPanelFirstName" cssClass="normalInput" path="firstName" />
            </div>
        </div>
    </div>
    <input type="hidden" id="and" name="and" value="true" />
    <form:hidden id="pageSize" path="pageSize" />
    <form:hidden id="pageNumber" path="pageNumber" />
    <form:hidden id="sortColumn" path="sortColumn" />
    <form:hidden id="ascending" path="ascending" />
    <form:hidden id="showFilterPanel" path="showFilterPanel" />
    <form:hidden id="searchBox" path="searchBox" />
    <input type="hidden" id="initSearchBox" name="initSearchBox" value="false" />
    <form:hidden id="roles" path="roles"/>
    <button class="purpleBtn" type="submit">Filter</button>
</form:form>
<!-- /.filterPanel -->
