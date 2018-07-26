/*
 * Copyright 2012-2013 TopCoder, Inc.
 *
 * This code was developed under U.S. government contract NNH10CD71C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.medicaid.services.impl;

import gov.medicaid.binders.BinderUtils;
import gov.medicaid.domain.model.EnrollmentType;
import gov.medicaid.entities.AcceptedAgreements;
import gov.medicaid.entities.Address;
import gov.medicaid.entities.Affiliation;
import gov.medicaid.entities.BeneficialOwner;
import gov.medicaid.entities.BinaryContent;
import gov.medicaid.entities.CMSUser;
import gov.medicaid.entities.ContactData;
import gov.medicaid.entities.ContactInformation;
import gov.medicaid.entities.DesignatedContact;
import gov.medicaid.entities.Document;
import gov.medicaid.entities.EmailTemplate;
import gov.medicaid.entities.Enrollment;
import gov.medicaid.entities.EnrollmentSearchCriteria;
import gov.medicaid.entities.EnrollmentStatus;
import gov.medicaid.entities.Entity;
import gov.medicaid.entities.License;
import gov.medicaid.entities.Note;
import gov.medicaid.entities.Organization;
import gov.medicaid.entities.OwnershipInformation;
import gov.medicaid.entities.PayToProvider;
import gov.medicaid.entities.Person;
import gov.medicaid.entities.PracticeLookup;
import gov.medicaid.entities.PracticeSearchCriteria;
import gov.medicaid.entities.ProfileHeader;
import gov.medicaid.entities.ProfileStatus;
import gov.medicaid.entities.ProviderCategoryOfService;
import gov.medicaid.entities.ProviderLookup;
import gov.medicaid.entities.ProviderProfile;
import gov.medicaid.entities.ProviderSearchCriteria;
import gov.medicaid.entities.ProviderService;
import gov.medicaid.entities.ProviderStatement;
import gov.medicaid.entities.RequestType;
import gov.medicaid.entities.SearchCriteria;
import gov.medicaid.entities.SearchResult;
import gov.medicaid.entities.SystemId;
import gov.medicaid.entities.UserRequest;
import gov.medicaid.entities.Validity;
import gov.medicaid.entities.dto.ViewStatics;
import gov.medicaid.services.NotificationService;
import gov.medicaid.services.PortalServiceException;
import gov.medicaid.services.ProviderEnrollmentService;
import gov.medicaid.services.util.Util;
import gov.medicaid.services.util.XMLAdapter;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.NotImplementedException;

import javax.ejb.EJB;
import javax.ejb.Local;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;
import javax.persistence.EntityGraph;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.servlet.http.HttpServletResponse;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialException;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * This implementation of the persistence interface takes full control of
 * mapping relationships in order to support multiple request tickets and
 * profile information on the same table.
 */
@Stateless
@Local(ProviderEnrollmentService.class)
@TransactionAttribute(TransactionAttributeType.REQUIRED)
@TransactionManagement(TransactionManagementType.CONTAINER)
public class ProviderEnrollmentServiceBean extends BaseService implements ProviderEnrollmentService {
    /**
     * Number of columns in the practice lookup results.
     */
    private static final int PRACTICE_COL_CNT = 9;

    /**
     * Number of columns in the practice lookup results.
     */
    private static final int TICKET_COL_CNT = 11;

    /**
     * Notification service used for sending emails.
     */
    @EJB
    private NotificationService notificationService;

    /**
     * List of roles with full access to all profiles and tickets.
     */
    private static final List<String> FULL_ACCESS = Arrays.asList(
            ViewStatics.ROLE_SERVICE_ADMINISTRATOR,
            ViewStatics.ROLE_SYSTEM_ADMINISTRATOR
    );

    /**
     * Empty constructor.
     */
    public ProviderEnrollmentServiceBean() {
    }

    /**
     * This method is used to delete a ticket draft. Note that only tickets in
     * draft status can be removed.
     *
     * @param user     the user performing the operation
     * @param ticketId the ticket id to be removed
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void removeDraftTicket(
            CMSUser user,
            long ticketId
    ) throws PortalServiceException {
        Enrollment ticket = getTicketDetails(user, ticketId);
        if (ticket.getStatus().getDescription().equals(ViewStatics.DRAFT_STATUS)) {
            purge(ticket.getDetails());
            getEm().remove(ticket);
        } else {
            throw new PortalServiceException("Cannot delete submitted tickets.");
        }
    }

    /**
     * This is the service method to be called after the process has completed
     * and resulted in a rejected change.
     *
     * @param user     the user who rejected the request
     * @param ticketId the ticket id that was rejected
     * @param reason   the reason for rejecting the request
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void rejectTicket(
            CMSUser user,
            long ticketId,
            String reason
    ) throws PortalServiceException {
        Enrollment ticket = getTicketDetails(user, ticketId);

        if (!ViewStatics.PENDING_STATUS.equals(ticket.getStatus().getDescription())) {
            throw new PortalServiceException("Cannot change status because it is not in pending state.");
        }

        EnrollmentType enrollment = XMLAdapter.toXML(ticket);
        notificationService.sendEnrollmentNotification(enrollment, EmailTemplate.REJECTED_ENROLLMENT);

        ticket.setStatus(findLookupByDescription(EnrollmentStatus.class, ViewStatics.REJECTED_STATUS));
        ticket.setStatusNote(reason);
        ticket.setStatusDate(Calendar.getInstance().getTime());
        saveTicket(user, ticket, false);
    }


    /**
     * This is the service method to be called after the process has completed
     * and resulted in an approved change, the ticket is approved AS-IS
     * (e.g. renewal) and there are no changes from the approver.
     *
     * @param user     the user who approved the request
     * @param ticketId the ticket id that was approved
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void approveTicket(
            CMSUser user,
            long ticketId
    ) throws PortalServiceException {
        approveTicket(user, getTicketDetails(user, ticketId));
    }

    /**
     * This is the service method to be called after the process has completed
     * and resulted in an approved change, but the approver made some manual
     * changes to the data so we don't reload from database before moving forward.
     *
     * @param user   the user who approved the request
     * @param ticket the ticket that will be approved (after saving it)
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void approveTicketWithChanges(
            CMSUser user,
            Enrollment ticket
    ) throws PortalServiceException {
        approveTicket(user, ticket);
    }

    /**
     * This is the method to be called to stream the attachment contents to the
     * given output stream directly from the database.
     *
     * @param user         the current user
     * @param attachmentId the attachment id to be streamed.
     * @param response     the response object to write the contents to
     * @throws IOException            for any I/O errors while streaming the
     *                                attachment contents
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void streamContent(
            CMSUser user,
            long attachmentId,
            HttpServletResponse response
    ) throws IOException, PortalServiceException {
        Document attachment = getEm().find(Document.class, attachmentId);
        if (attachment != null) {
            if (attachment.getTicketId() > 0) {
                checkTicketEntitlement(user, attachment.getTicketId());
            } else if (attachment.getProfileId() > 0) {
                checkProfileEntitlement(user, attachment.getProfileId());
            } else {
                if (!attachment.getCreatedBy().equals(user.getUserId())) {
                    throw new PortalServiceException("Access Denied.");
                }
            }

            BinaryContent content = getEm().find(BinaryContent.class, attachment.getContentId());
            try (InputStream input = content.getContent().getBinaryStream()) {
                response.setContentType(attachment.getType());
                response.setHeader(
                        "Content-Disposition",
                        "attachment; filename=" + attachment.getFilename()
                );
                IOUtils.copy(input, response.getOutputStream());
            } catch (SQLException e) {
                throw new IOException("Cannot read binary content from database.", e);
            }
        }
    }

    /**
     * This method gets all the providers that meet the search criteria. If none
     * available, the search result will be empty.
     *
     * @param user     the user performing the action
     * @param criteria the search criteria
     * @return the applicable providers
     * @throws IllegalArgumentException if any argument is null, or the page
     *                                  size and page number settings are
     *                                  invalid
     */
    @SuppressWarnings("unchecked")
    @Override
    public SearchResult<UserRequest> searchTickets(
            CMSUser user,
            ProviderSearchCriteria criteria
    ) {
        if (criteria == null) {
            throw new IllegalArgumentException("Criteria cannot be null.");
        }
        if (user == null || user.getRole() == null) {
            throw new IllegalArgumentException("User and the corresponding role cannot be null.");
        }
        if (Util.isNotBlank(criteria.getEnrollmentNumber()) && !Util.isDigits(criteria.getEnrollmentNumber())) {
            throw new IllegalArgumentException("Invalid number format for enrollment number.");
        }

        checkSortColumn(criteria, TICKET_COL_CNT);

        SearchResult<UserRequest> results = new SearchResult<>();
        results.setPageNumber(criteria.getPageNumber());
        results.setPageSize(criteria.getPageSize());

        String fromClause = "FROM ProviderProfile p LEFT JOIN p.riskLevel rl, Enrollment t LEFT JOIN t.requestType rt "
                + "LEFT JOIN t.status ts, Entity e LEFT JOIN e.providerType pt WHERE p.profileId = t.profileReferenceId "
                + "AND p.profileId = e.profileId AND e.ticketId = t.enrollmentId ";

        StringBuilder countQuery = new StringBuilder("SELECT count(*) " + fromClause);
        appendCriteria(countQuery, user, criteria);

        Query count = getEm().createQuery(countQuery.toString());
        bindParameters(count, user, criteria);
        results.setTotal(((Number) count.getSingleResult()).intValue());

        StringBuilder fetchQuery = new StringBuilder(
                "SELECT NEW gov.medicaid.entities.UserRequest(t.enrollmentId, e.npi, t.submissionDate, "
                        + "rt.description, ts.description, t.statusDate, rl.description, pt.description, "
                        + "e.name, t.createdOn, rl.sortIndex, t.processInstanceId, t.profileReferenceId, "
                        + "t.enrollmentId = p.enrollmentId) " + fromClause);

        appendCriteria(fetchQuery, user, criteria);
        appendSorting(fetchQuery, criteria);

        Query items = getEm().createQuery(fetchQuery.toString());
        bindParameters(items, user, criteria);
        if (criteria.getPageSize() > 0) {
            int offset = (criteria.getPageNumber() - 1) * criteria.getPageSize();
            items.setFirstResult(offset);
            items.setMaxResults(criteria.getPageSize());
        }

        results.setItems(items.getResultList());
        return results;
    }

    /**
     * This method gets all the enrollments that meet the search criteria. If none
     * available, the search result will be empty.
     *
     * @param criteria the search criteria
     * @return the applicable providers
     * @throws IllegalArgumentException if any argument is null, or the page
     *                                  size and page number settings are
     *                                  invalid
     */
    @SuppressWarnings("unchecked")
    @Override
    public SearchResult<Enrollment> searchEnrollments(
            EnrollmentSearchCriteria criteria
    ) {
        if (criteria == null) {
            throw new IllegalArgumentException("Criteria cannot be null.");
        }

        SearchResult<Enrollment> results = new SearchResult<>();
        results.setPageNumber(criteria.getPageNumber());
        results.setPageSize(criteria.getPageSize());

        String baseClause = "FROM Enrollment t " +
            "LEFT JOIN t.status ts, Entity e " +
            "LEFT JOIN e.providerType pt " +
            "WHERE e.ticketId = t.enrollmentId " +
            "AND ts.description in (:enrollmentStatuses) ";

        StringBuilder countQuery = new StringBuilder("SELECT count(*) " + baseClause);
        appendCriteria(countQuery, criteria);

        Query count = getEm().createQuery(countQuery.toString());
        count.setParameter("enrollmentStatuses",
            Arrays.asList(ViewStatics.APPROVED_STATUS, ViewStatics.REJECTED_STATUS));
        bindParameters(count, criteria);
        results.setTotal(((Number) count.getSingleResult()).intValue());

        StringBuilder fetchQuery = new StringBuilder("SELECT t " + baseClause);

        appendCriteria(fetchQuery, criteria);
        appendSorting(fetchQuery, criteria);

        Query items = getEm().createQuery(fetchQuery.toString());
        items.setParameter("enrollmentStatuses",
            Arrays.asList(ViewStatics.APPROVED_STATUS, ViewStatics.REJECTED_STATUS));
        bindParameters(items, criteria);
        if (criteria.getPageSize() > 0) {
            int offset = (criteria.getPageNumber() - 1) * criteria.getPageSize();
            items.setFirstResult(offset);
            items.setMaxResults(criteria.getPageSize());
        }

        results.setItems(items.getResultList());
        return results;
    }

    /**
     * This method gets all the enrollments that are draft status at end of
     * month, and that meet the search criteria. If none available, the
     * search result will be empty.
     *
     * @param criteria the search criteria
     * @return the enrollments
     * @throws IllegalArgumentException if any argument is null, or the page
     *                                  size and page number settings are
     *                                  invalid
     */
    @SuppressWarnings("unchecked")
    @Override
    public SearchResult<Enrollment> getDraftAtEomEnrollments(
            EnrollmentSearchCriteria criteria
    ) {
        if (criteria == null) {
            throw new IllegalArgumentException("Criteria cannot be null.");
        }

        SearchResult<Enrollment> results = new SearchResult<>();
        results.setPageNumber(criteria.getPageNumber());
        results.setPageSize(criteria.getPageSize());

        String baseClause = "FROM Enrollment t LEFT JOIN t.status ts WHERE " +
          " (t.submissionDate IS NULL OR " +
          "  MONTH(t.createdOn) != MONTH(t.submissionDate) " +
          "  OR YEAR(t.createdOn) != YEAR(t.submissionDate)) ";

        StringBuilder countQuery = new StringBuilder("SELECT count(*) " + baseClause);
        appendCriteria(countQuery, criteria);

        Query count = getEm().createQuery(countQuery.toString());
        bindParameters(count, criteria);
        results.setTotal(((Number) count.getSingleResult()).intValue());

        StringBuilder fetchQuery = new StringBuilder("SELECT t " + baseClause);

        appendCriteria(fetchQuery, criteria);
        appendSorting(fetchQuery, criteria);

        Query items = getEm().createQuery(fetchQuery.toString());
        bindParameters(items, criteria);
        if (criteria.getPageSize() > 0) {
            int offset = (criteria.getPageNumber() - 1) * criteria.getPageSize();
            items.setFirstResult(offset);
            items.setMaxResults(criteria.getPageSize());
        }

        results.setItems(items.getResultList());
        return results;
    }

    /**
     * Saves the given ticket as draft.
     *
     * @param user   the user saving the ticket.
     * @param ticket the ticket to be saved
     * @return the ticket id for the draft created
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public long saveAsDraft(
            CMSUser user,
            Enrollment ticket
    ) throws PortalServiceException {
        if (ticket.getRequestType() == null) {
            ticket.setRequestType(findLookupByDescription(RequestType.class, ViewStatics.ENROLLMENT_REQUEST));
        }

        if (ticket.getDetails() == null) {
            throw new PortalServiceException("Invalid API call, please provide ticket details.");
        }

        ticket.setStatus(findLookupByDescription(EnrollmentStatus.class, ViewStatics.DRAFT_STATUS));
        ticket.setStatusDate(Calendar.getInstance().getTime());

        if (ticket.getEnrollmentId() == 0) {
            ticket.setCreatedBy(user);
            ticket.setCreatedOn(Calendar.getInstance().getTime());
            return saveTicket(user, ticket, ticket.getDetails().getProfileId() == 0);
        } else {
            return saveTicket(user, ticket, false);
        }

    }

    @Override
    public Optional<Enrollment> getEnrollment(
            CMSUser user,
            long enrollmentId
    ) throws PortalServiceException {
        return getEnrollment(user, enrollmentId, null);
    }

    @Override
    public Optional<Enrollment> getEnrollmentWithScreenings(
            CMSUser user,
            long enrollmentId
    ) throws PortalServiceException {
        return getEnrollment(
                user,
                enrollmentId,
                "Enrollment with screenings"
        );
    }

    /**
     * This is the service method to be called after the process has completed
     * and resulted in an approved change.
     */
    private void approveTicket(
            CMSUser user,
            Enrollment ticket
    ) throws PortalServiceException {
        if (!ViewStatics.PENDING_STATUS.equals(ticket.getStatus().getDescription())
                && !ViewStatics.PENDING_STATUS.equals(ticket.getStatus().getDescription())) {
            throw new PortalServiceException("Cannot change status because it is not in pending state.");
        }

        // change status
        ticket.setStatus(findLookupByDescription(EnrollmentStatus.class, ViewStatics.APPROVED_STATUS));
        ticket.setStatusDate(Calendar.getInstance().getTime());

        ProviderProfile profile = ticket.getDetails();

        // set audit
        profile.setModifiedBy(ticket.getSubmittedBy().getUserId());
        profile.setModifiedOn(ticket.getStatusDate());

        if (ticket.getRequestType().getDescription().equals(ViewStatics.ENROLLMENT_REQUEST) ||
                ticket.getRequestType().getDescription().equals(ViewStatics.RENEWAL_REQUEST) ||
                ticket.getRequestType().getDescription().equals(ViewStatics.UPDATE_REQUEST)) {
            profile.setProfileStatus(findLookupByDescription(ProfileStatus.class, "Active"));
            profile.setOwnerId(ticket.getSubmittedBy().getUserId());
            profile.setCreatedBy(ticket.getSubmittedBy().getUserId());
            profile.setCreatedOn(ticket.getStatusDate());

            profile.getEntity().setEnrolled("Y");
        } else if (ticket.getRequestType().getDescription().equals(ViewStatics.IMPORT_REQUEST)) {
            throw new PortalServiceException("Cannot handle import requests");
            /*
                // commented out code because, while disabling, not deleting until we remove
                // the whole process of requesting imports
                profile.setProfileStatus(findLookupByDescription(ProfileStatus.class, "Active"));
                profile.setOwnerId(ticket.getSubmittedBy().getUserId());
                profile.getEntity().setEnrolled("Y");
                insertProfile(0, profile);
            */
        } else {
            throw new PortalServiceException("Cannot handle request type: " +
                    ticket.getRequestType().getDescription());
        }

        // notes are special because they can be added directly to the profile
        // we handle them separately so they are merged during approval
        promoteNotesToBase(profile.getProfileId(), profile.getEnrollmentId());

        promoteCOSToBase(user, profile.getProfileId(), profile.getEnrollmentId());

        saveTicket(user, ticket, false);
    }

    /**
     * Look up an enrollment application by its ID, optionally fetching the
     * named entity graph, and checking that the given user has permission to
     * access the enrollment.
     *
     * @param user            the requesting user; used for authorization
     * @param enrollmentId    the ID of the enrollment
     * @param entityGraphName the optional name of the entity graph;
     *                        fetch the default graph if null.
     * @return the enrollment, if found; empty, if not found.
     * @throws PortalServiceException   if not authorized
     * @throws IllegalArgumentException if there is no entity graph with the
     *                                  given name
     */
    private Optional<Enrollment> getEnrollment(
            CMSUser user,
            long enrollmentId,
            String entityGraphName
    ) throws PortalServiceException {
        checkTicketEntitlement(user, enrollmentId);

        return getEnrollment(enrollmentId, entityGraphName);
    }

    private Optional<Enrollment> getEnrollment(
            long enrollmentId,
            String entityGraphName
    ) throws PortalServiceException {
        Map<String, Object> hints = new HashMap<>();
        if (entityGraphName != null) {
            hints = hintEntityGraph(entityGraphName);
        }

        Enrollment enrollment = getEm().find(
                Enrollment.class,
                enrollmentId,
                hints
        );
        if (enrollment == null) {
            return Optional.empty();
        }

        fetchChildren(enrollment);

        ProviderProfile providerProfile = getProviderDetails(
                enrollment.getProfileReferenceId(),
                true
        );
        if (providerProfile == null) {
            return Optional.empty();
        }

        enrollment.setDetails(providerProfile);
        return Optional.of(enrollment);
    }

    @Override
    public Enrollment getTicketDetails(
            CMSUser user,
            long ticketId
    ) throws PortalServiceException {
        return getEnrollment(
                user,
                ticketId
        ).orElse(null);
    }

    /**
     * Retrieves the provider details (full).
     *
     * @param user      the user getting the provider.
     * @param profileId the profile id to get the details for
     * @return the complete provider profile
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public ProviderProfile getProviderDetails(
            CMSUser user,
            long profileId
    ) throws PortalServiceException {
        checkProfileEntitlement(user, profileId);
        ProviderProfile providerDetails = getProviderDetails(profileId, true);
        if (providerDetails == null) {
            return null;
        }
        return providerDetails.clone();
    }

    /**
     * Gets the profile by id.
     *
     * @param profileId     the profile id
     * @param fetchChildren true if the full object is needed
     * @return the profile with the given id
     */
    @Override
    public ProviderProfile getProviderDetails(
            long profileId,
            boolean fetchChildren
    ) {
        TypedQuery<ProviderProfile> query =
            getEm().createQuery(
                "FROM ProviderProfile p WHERE profileId = :profileId",
                ProviderProfile.class
            );
        query.setParameter("profileId", profileId);
        List<ProviderProfile> rs = query.getResultList();
        if (rs.isEmpty()) {
            return null;
        }

        ProviderProfile profile = rs.get(0);
        if (fetchChildren) {
            fetchChildren(profile);
        }
        return profile;
    }

    /**
     * Search for provider by NPI.
     *
     * @param npi the National Provider Identifier to search by
     * @return the matching practice results
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public List<ProviderLookup> lookupProvider(
            String npi
    ) {
        String fetchQuery =
                "SELECT NEW gov.medicaid.entities.ProviderLookup(e.profileId, e.npi, e.name, e.providerType.description) "
                + "FROM Entity e WHERE e.npi = :npi AND ticketId = 0 AND e.enrolled = 'Y'";
        Query items = getEm().createQuery(fetchQuery);
        items.setParameter("npi", npi);
        List resultList = items.getResultList();
        populateContactPerson(resultList);
        return resultList;
    }

    /**
     * Search for practice.
     *
     * @param user     the user performing the search
     * @param criteria the criteria filter
     * @return the matching practice results
     */
    @SuppressWarnings("unchecked")
    @Override
    public SearchResult<PracticeLookup> searchPractice(
            CMSUser user,
            PracticeSearchCriteria criteria
    ) {
        if (criteria == null) {
            throw new IllegalArgumentException("Criteria cannot be null.");
        }
        if (user == null || user.getRole() == null) {
            throw new IllegalArgumentException("User and the corresponding role cannot be null.");
        }
        if (criteria.getPageNumber() < 0 || (criteria.getPageNumber() > 0 && criteria.getPageSize() <= 0)) {
            throw new IllegalArgumentException("Invalid page number/page size combination.");
        }

        checkSortColumn(criteria, PRACTICE_COL_CNT);

        SearchResult<PracticeLookup> results = new SearchResult<>();
        results.setPageNumber(criteria.getPageNumber());
        results.setPageSize(criteria.getPageSize());

        String fromClause = "FROM Organization e LEFT JOIN e.contactInformation ci LEFT JOIN ci.address a "
                + "WHERE e.enrolled = 'Y'";

        StringBuilder countQuery = new StringBuilder("SELECT count(*) " + fromClause);
        appendCriteria(countQuery, criteria);

        Query count = getEm().createQuery(countQuery.toString());
        bindParameters(count, criteria);
        results.setTotal(((Number) count.getSingleResult()).intValue());

        StringBuilder fetchQuery = new StringBuilder(
                "SELECT NEW gov.medicaid.entities.PracticeLookup(e.profileId, e.npi, e.name, "
                        + "ci.address.line1, ci.address.line2, ci.address.city, ci.address.state, "
                        + "ci.address.zipcode, ci.address.county, ci.phoneNumber, ci.faxNumber, "
                        + "e.backgroundStudyId, e.backgroundClearanceDate, e.agencyId) " + fromClause);

        appendCriteria(fetchQuery, criteria);
        appendSorting(fetchQuery, criteria);

        Query items = getEm().createQuery(fetchQuery.toString());
        bindParameters(items, criteria);
        if (criteria.getPageNumber() > 0) {
            int offset = (criteria.getPageNumber() - 1) * criteria.getPageSize();
            items.setFirstResult(offset);
            items.setMaxResults(criteria.getPageSize());
        }

        results.setItems(items.getResultList());

        if (criteria.isAgency()) {
            populateContact(items.getResultList());
        }
        return results;
    }

    /**
     * Populates the contact names for the given list.
     *
     * @param resultList the list to be populated
     */
    @SuppressWarnings("unchecked")
    private void populateContact(List<PracticeLookup> resultList) {
        if (resultList == null || resultList.isEmpty()) {
            return;
        }

        Map<Long, PracticeLookup> map = new HashMap<>();
        for (PracticeLookup practiceLookup : resultList) {
            map.put(practiceLookup.getProfileId(), practiceLookup);
        }

        String fetchQuery =
                "SELECT NEW gov.medicaid.entities.ContactData(dc.profileId, dc.person.name) "
                + "FROM DesignatedContact dc WHERE dc.type = 'ENROLLMENT' AND dc.profileId IN (:profileIds)";
        Query q = getEm().createQuery(fetchQuery);
        q.setParameter("profileIds", new ArrayList<>(map.keySet()));
        List<ContactData> rs = q.getResultList();
        for (ContactData contactData : rs) {
            PracticeLookup match = map.get(contactData.getProfileId());
            if (match != null) {
                match.setContactName(contactData.getName());
            }
        }
    }

    /**
     * Populates the contact names for the given list.
     *
     * @param resultList the list to be populated
     */
    @SuppressWarnings("unchecked")
    private void populateContactPerson(List<ProviderLookup> resultList) {
        if (resultList == null || resultList.isEmpty()) {
            return;
        }

        Map<Long, ProviderLookup> map = new HashMap<>();
        for (ProviderLookup practiceLookup : resultList) {
            map.put(practiceLookup.getProfileId(), practiceLookup);
        }

        String fetchQuery =
                "SELECT NEW gov.medicaid.entities.ContactData(dc.profileId, dc.person.name, dc.person.contactInformation.phoneNumber) "
                + "FROM DesignatedContact dc WHERE dc.type = 'ENROLLMENT' AND dc.profileId IN (:profileIds)";
        Query q = getEm().createQuery(fetchQuery);
        q.setParameter("profileIds", new ArrayList<>(map.keySet()));
        List<ContactData> rs = q.getResultList();
        for (ContactData contactData : rs) {
            ProviderLookup match = map.get(contactData.getProfileId());
            if (match != null) {
                match.setContactName(contactData.getName());
                String[] phone = BinderUtils.splitPhone(contactData.getPhoneNumber());
                match.setPhone1(phone[0]);
                match.setPhone2(phone[1]);
                match.setPhone3(phone[2]);
                match.setPhone4(phone[3]);
            }
        }
    }

    /**
     * This method gets all the providers owned by the given user. If none
     * available, the search result will be empty.
     *
     * @param user the user performing the action
     * @return - the applicable providers
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<ProfileHeader> findMyProfiles(CMSUser user) {
        if (user == null || user.getRole() == null) {
            throw new IllegalArgumentException("User and the corresponding role cannot be null.");
        }

        // If not the creator, then user has proxy access via the NPI ID.
        boolean hasProxyForNpi = user.getProxyForNPI() != null;

        String fetchQuery = "SELECT NEW gov.medicaid.entities.ProfileHeader("
                + "e.profileId, e.npi, pt.description, p.effectiveDate, p.modifiedOn"
                + ") FROM ProviderProfile p, Entity e LEFT JOIN e.providerType pt "
                + "WHERE e.ticketId = p.enrollmentId AND p.profileId = e.profileId and "
                + (hasProxyForNpi ? "e.npi = :npi " : "p.ownerId = :ownerId ")
                + "ORDER BY 5 DESC";

        Query items = getEm().createQuery(fetchQuery);

        if (hasProxyForNpi) {
            items.setParameter("npi", user.getProxyForNPI());
        } else {
            items.setParameter("ownerId", user.getUserId());
        }
        return items.getResultList();
    }

    /**
     * Creates a renewal ticket from the given profile id.
     *
     * @param user      the user performing the action
     * @param profileId the profile to be renewed
     * @return the generated ticket
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public Enrollment renewProfile(
            CMSUser user,
            long profileId
    ) throws PortalServiceException {
        ProviderProfile provider = getProviderDetails(user, profileId);
        if (provider == null) {
            throw new PortalServiceException("The requested profile was not found.");
        }
        Enrollment ticket = new Enrollment();
        ticket.setRequestType(findLookupByDescription(RequestType.class, ViewStatics.RENEWAL_REQUEST));
        ticket.setDetails(provider.clone());
        ticket.setAgreements(Collections.emptyList());
        ticket.setReferenceTimestamp(provider.getModifiedOn());
        return ticket;
    }

    /**
     * Creates an update ticket from the given profile id.
     *
     * @param user      the user performing the action
     * @param profileId the profile to be edited
     * @return the generated ticket
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public Enrollment editProfile(
            CMSUser user,
            long profileId
    ) throws PortalServiceException {
        ProviderProfile provider = getProviderDetails(user, profileId);
        Enrollment ticket = new Enrollment();
        ticket.setRequestType(findLookupByDescription(RequestType.class, ViewStatics.UPDATE_REQUEST));
        ticket.setDetails(provider.clone());
        ticket.setAgreements(Collections.emptyList());
        ticket.setReferenceTimestamp(provider.getModifiedOn());
        return ticket;
    }

    /**
     * Uploads an attachment to the database.
     *
     * @param user       the user performing the action
     * @param attachment the attachment to be uploaded
     * @return the attachment id generated
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public long uploadAttachment(
            CMSUser user,
            Document attachment
    ) throws PortalServiceException {
        if (attachment.getId() <= 0) {
            attachment.setCreatedOn(Calendar.getInstance().getTime());
            attachment.setCreatedBy(user.getUserId());
        }

        if (attachment.getTicketId() > 0) {
            checkTicketEntitlement(user, attachment.getTicketId());
        } else if (attachment.getProfileId() > 0) {
            checkProfileEntitlement(user, attachment.getProfileId());
        }

        return uploadAttachment(attachment);
    }

    /**
     * Creates a note on a request, the note will remain on the request until it
     * is approved.
     *
     * @param user     the user performing the action
     * @param ticketId the request identifier
     * @param text     the note text
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void addNoteToTicket(
            CMSUser user,
            long ticketId,
            String text
    ) throws PortalServiceException {
        checkTicketEntitlement(user, ticketId);

        insertNote(user, getTicketDetails(user, ticketId).getProfileReferenceId(), ticketId, text);
    }

    /**
     * Creates a note on a profile, the note will also be visible on all active
     * requests.
     *
     * @param user      the user performing the action
     * @param profileId the request identifier
     * @param text      the note text
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void addNoteToProfile(
            CMSUser user,
            long profileId,
            String text
    ) throws PortalServiceException {
        checkProfileEntitlement(user, profileId);
        insertNote(user, profileId, 0, text);
    }

    /**
     * Retrieves the ticket details (full).
     *
     * @param user              the user getting the ticket.
     * @param processInstanceId the process instance id to get the details for
     * @return the complete ticket and provider profile
     * @throws PortalServiceException for any errors encountered
     */
    @SuppressWarnings("rawtypes")
    @Override
    public Enrollment getTicketByProcessInstanceId(
            CMSUser user,
            long processInstanceId
    ) throws PortalServiceException {
        Query q = getEm().createQuery("FROM Enrollment e where e.processInstanceId = :processInstanceId");
        q.setParameter("processInstanceId", processInstanceId);
        List rs = q.getResultList();
        if (rs.isEmpty()) {
            return null;
        }

        Enrollment ticket = (Enrollment) rs.get(0); // should be unique per process instance
        checkTicketEntitlement(user, ticket.getEnrollmentId());
        ticket.setDetails(getProviderDetails(ticket.getProfileReferenceId(), true));
        return ticket;
    }

    /**
     * Used by data onboarding service to fully import a mapped profile.
     *
     * @param user         the user performing the operation
     * @param sourceSystem the source of the imported profile
     * @param profile      the profile to be created
     * @return the assigned internal id
     */
    @Override
    public long importProfile(
            CMSUser user,
            SystemId sourceSystem,
            ProviderProfile profile
    ) {
        throw new NotImplementedException("Profile import is not yet supported.");
    }

    /**
     * Retrieves the related entity for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related entity to the profile
     */
    @Override
    public Entity findEntityByProviderKey(Long profileId, Long ticketId) {
        String queryStr = "FROM Entity e WHERE 1 = 1 ";
        if (profileId != null) {
            queryStr += " AND profileId = :profileId";
        }
        if (ticketId != null) {
            queryStr += " AND ticketId = :ticketId";
        }
        TypedQuery<Entity> query = getEm().createQuery(queryStr, Entity.class);

        if (profileId != null) {
            query.setParameter("profileId", profileId);
        }
        if (ticketId != null) {
            query.setParameter("ticketId", ticketId);
        }

        List<Entity> rs = query.getResultList();
        if (rs.isEmpty()) {
            return null;
        }
        return rs.get(0);
    }

    /**
     * Retrieves the related attachments for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related attachments to the profile
     */
    @Override
    public List<Document> findAttachments(Long profileId, Long ticketId) {
        String queryStr = "FROM Document a WHERE 1 = 1 ";
        if (profileId != null) {
            queryStr += " AND profileId = :profileId";
        }
        if (ticketId != null) {
            queryStr += " AND ticketId = :ticketId";
        }
        TypedQuery<Document> query = getEm().createQuery(queryStr, Document.class);

        if (profileId != null) {
            query.setParameter("profileId", profileId);
        }
        if (ticketId != null) {
            query.setParameter("ticketId", ticketId);
        }
        return query.getResultList();
    }

    /**
     * Inserts the given attachment and its contents.
     *
     * @param attachment the attachment to create
     * @return the attachment id
     * @throws PortalServiceException for any errors encountered
     */
    private long uploadAttachment(
            Document attachment
    ) throws PortalServiceException {
        saveContentsAndCloseStreams(attachment);

        getEm().persist(attachment);
        return attachment.getId();
    }

    /**
     * Saves the attachment content and closes the stream. The content id is
     * then set to a reference to the content saved.
     *
     * @param attachment the attachment to be saved
     * @throws PortalServiceException for any errors encountered
     */
    private void saveContentsAndCloseStreams(
            Document attachment
    ) throws PortalServiceException {
        if (attachment.getStream() != null) {
            try {
                String contentId = saveAttachmentContent(attachment.getStream());
                attachment.setContentId(contentId);
            } catch (IOException e) {
                throw new PortalServiceException("Cannot save binary content to database.", e);
            } catch (SerialException e) {
                throw new PortalServiceException("Cannot save binary content to database.", e);
            } catch (SQLException e) {
                throw new PortalServiceException("Cannot save binary content to database.", e);
            } finally {
                try {
                    attachment.getStream().close();
                } catch (IOException e) {
                    throw new PortalServiceException("Cannot close attachment stream.", e);
                }
                attachment.setStream(null);
            }
        }
    }

    /**
     * Checks for the ticket entitlement.
     *
     * @param user     the user performing the action
     * @param ticketId the ticket being processed
     * @throws PortalServiceException if the user does not own or cannot access the entity
     */
    @SuppressWarnings("rawtypes")
    private void checkTicketEntitlement(
            CMSUser user,
            long ticketId
    ) throws PortalServiceException {
        if (!FULL_ACCESS.contains(user.getRole().getDescription())) {
            if (user.getProxyForNPI() == null) {
                Query q = getEm().createQuery(
                        "SELECT 1 FROM Enrollment t WHERE t.enrollmentId = :enrollmentId AND t.createdBy = :user");
                q.setParameter("user", user);
                q.setParameter("enrollmentId", ticketId);
                List rs = q.getResultList();
                if (rs.isEmpty()) {
                    throw new PortalServiceException("Access Denied.");
                }
            } else {
                // not the creator but given proxy access via the NPI ID
                long profileId = getTicketDetails(user, ticketId).getProfileReferenceId();
                Entity entity = findEntityByProviderKey(profileId, ticketId);
                if (!entity.getNpi().equals(user.getProxyForNPI())) {
                    throw new PortalServiceException("You have no access to the requested ticket.");
                }
            }
        }
    }

    /**
     * Checks for the profile entitlement.
     *
     * @param user      the user performing the action
     * @param profileId the profile being processed
     * @throws PortalServiceException if the user does not own or cannot access the entity
     */
    @SuppressWarnings("rawtypes")
    private void checkProfileEntitlement(
            CMSUser user,
            long profileId
    ) throws PortalServiceException {
        if (!FULL_ACCESS.contains(user.getRole().getDescription())) {
            if (user.getProxyForNPI() == null) {
                Query q = getEm().createQuery(
                        "SELECT 1 FROM ProviderProfile p WHERE p.profileId = :profileId "
                                + "AND p.ownerId = :username");
                q.setParameter("username", user.getUserId());
                q.setParameter("profileId", profileId);
                List rs = q.getResultList();
                if (rs.isEmpty()) {
                    throw new PortalServiceException("Access Denied.");
                }
            } else {
                // not the creator but given proxy access via the NPI ID
                Entity entity = findEntityByProviderKey(profileId, 0L);
                if (!entity.getNpi().equals(user.getProxyForNPI())) {
                    throw new PortalServiceException("You have no access to the requested profile.");
                }
            }
        }
    }

    private void saveRelatedEntities(Enrollment enrollment) {
        insertAgreements(enrollment);
    }

    /**
     * Inserts a brand new copy of the given provider profile.
     *
     * @param ticketId the ticket to insert the details for
     * @param details  the details to insert
     * @throws PortalServiceException for any errors encountered
     */
    private void insertProfile(
            long ticketId,
            ProviderProfile details
    ) throws PortalServiceException {
        // persist parent
        details.setEnrollmentId(ticketId);

        getEm().persist(details);

        saveRelatedEntities(details);
    }

    private void saveRelatedEntities(
            ProviderProfile details
    ) throws PortalServiceException {
        // save profile owner
        insertProviderEntity(details);

        // save designated contacts
        insertDesignatedContacts(details);

        // save attachments
        Map<Long, Long> attachmentMapping = insertAttachments(details);

        // save certifications
        insertCertifications(details, attachmentMapping);

        // save affiliations
        insertAffiliations(details, attachmentMapping);

        // save statement
        insertStatement(details);

        // save ownership information
        insertOwnershipInfo(details);

        // save setup information
        insertSetupInfo(details);

        // save services information
        insertServicesInfo(details);
    }

    private void insertServicesInfo(ProviderProfile details) {
        List<ProviderService> services = details.getServices();
        if (services == null || services.isEmpty()) {
            return;
        }

        for (ProviderService service : services) {
            service.setTicketId(details.getEnrollmentId());
            service.setProfileId(details.getProfileId());
            service.setId(0);
            getEm().persist(service);
        }

    }

    private void insertSetupInfo(ProviderProfile details) {
        List<PayToProvider> payToProviders = details.getPayToProviders();
        if (payToProviders == null) {
            return;
        }

        for (PayToProvider payToProvider : payToProviders) {
            payToProvider.setProfileId(details.getProfileId());
            payToProvider.setTicketId(details.getEnrollmentId());
            payToProvider.setId(0);
            getEm().persist(payToProvider);
        }
    }

    /**
     * Saves ownership information.
     *
     * @param details the provider profile
     */
    private void insertOwnershipInfo(ProviderProfile details) {
        OwnershipInformation ownership = details.getOwnershipInformation();
        if (ownership == null) {
            return;
        }

        ownership.setProfileId(details.getProfileId());
        ownership.setTicketId(details.getEnrollmentId());

        ownership.setId(0);
        // save owners
        insertBeneficialOwners(ownership);

        getEm().persist(ownership);
    }

    /**
     * Saves the ownership information beneficial owners.
     *
     * @param ownership the ownership information
     */
    private void insertBeneficialOwners(OwnershipInformation ownership) {
        List<BeneficialOwner> owners = ownership.getBeneficialOwners();
        if (owners == null || owners.isEmpty()) {
            return;
        }

        for (BeneficialOwner owner : owners) {
            insertAddress(owner.getAddress());
            insertAddress(owner.getOtherProviderAddress());
            owner.setId(0);
            getEm().persist(owner);
        }
    }

    private void insertAgreements(Enrollment enrollment) {
        List<AcceptedAgreements> agreements = enrollment.getAgreements();

        if (agreements == null || agreements.isEmpty()) {
            return;
        }

        for (AcceptedAgreements acceptedAgreements : agreements) {
            acceptedAgreements.setTicketId(enrollment.getEnrollmentId());

            acceptedAgreements.setId(0);
            getEm().persist(acceptedAgreements);
        }
    }

    /**
     * Inserts the provider profile statement.
     *
     * @param details the provider profile
     */
    private void insertStatement(
            ProviderProfile details
    ) {
        ProviderStatement statement = details.getStatement();
        if (statement == null) {
            return;
        }

        statement.setTicketId(details.getEnrollmentId());
        statement.setProfileId(details.getProfileId());

        statement.setId(0);
        getEm().persist(statement);
    }

    /**
     * Inserts the provider affiliations.
     *
     * @param details           the provider profile
     * @param attachmentMapping attachment id mapping
     * @throws PortalServiceException for any errors encountered
     */
    private void insertAffiliations(
            ProviderProfile details,
            Map<Long, Long> attachmentMapping
    ) throws PortalServiceException {
        List<Affiliation> affiliations = details.getAffiliations();
        if (affiliations == null || affiliations.isEmpty()) {
            return;
        }

        for (Affiliation affiliation : affiliations) {
            affiliation.setProfileId(details.getProfileId());

            if (affiliation.getTargetProfileId() == 0) { // manually entered affiliation
                if (affiliation.getEntity() == null) {
                    throw new PortalServiceException("Referenced profile id or provider information must be provided");
                }
                Entity entity = affiliation.getEntity();
                insertNonProviderEntity(entity);
                affiliation.setTargetEntityId(entity.getId());

                List<License> certifications = affiliation.getAffiliateLicenses();
                if (certifications != null) {
                    for (License license : certifications) {
                        license.setAffiliateId(entity.getId());

                        if (license.getAttachmentId() > 0) {
                            if (!attachmentMapping.containsKey(license.getAttachmentId())) {
                                throw new PortalServiceException("License references an invalid attachment");
                            }
                            license.setAttachmentId(attachmentMapping.get(license.getAttachmentId()));
                        }
                        license.setId(0);
                        getEm().persist(license);
                    }
                }
            } else {
                affiliation.setTargetEntityId(0);
            }

            affiliation.setId(0);
            getEm().persist(affiliation);
        }
    }

    /**
     * Inserts the provider attachments.
     *
     * @param details the provider profile
     * @return the attachment id mapping
     * @throws PortalServiceException for any errors encountered
     */
    private Map<Long, Long> insertAttachments(
            ProviderProfile details
    ) throws PortalServiceException {
        HashMap<Long, Long> mapping = new HashMap<>();

        List<Document> attachments = details.getAttachments();
        if (attachments == null || attachments.isEmpty()) {
            return mapping;
        }

        for (Document attachment : attachments) {
            attachment.setTicketId(details.getEnrollmentId());
            attachment.setProfileId(details.getProfileId());

            if (attachment.getId() > 0) {
                // regenerate attachment id when cloning, but keep reference so we can
                // fix non-mapped references
                long oldAttachmentId = attachment.getId();
                getEm().detach(attachment);
                attachment.setId(0);
                long newAttachmentId = uploadAttachment(attachment);
                mapping.put(oldAttachmentId, newAttachmentId);
            } else {
                uploadAttachment(attachment);
            }
        }

        return mapping;
    }

    /**
     * Inserts the provider certifications.
     *
     * @param details           the provider profile
     * @param attachmentMapping the attachment id mapping
     * @throws PortalServiceException for any errors encountered
     */
    private void insertCertifications(
            ProviderProfile details,
            Map<Long, Long> attachmentMapping
    ) throws PortalServiceException {
        List<License> certifications = details.getCertifications();
        if (certifications == null || certifications.isEmpty()) {
            return;
        }

        for (License license : certifications) {
            license.setTicketId(details.getEnrollmentId());
            license.setProfileId(details.getProfileId());

            if (license.getAttachmentId() > 0) {
                if (!attachmentMapping.containsKey(license.getAttachmentId())) {
                    throw new PortalServiceException("License references an invalid attachment");
                }
                license.setAttachmentId(attachmentMapping.get(license.getAttachmentId()));
            }
            license.setId(0);
            getEm().persist(license);
        }
    }

    /**
     * Inserts the provider header data.
     *
     * @param details the provider profile
     */
    private void insertProviderEntity(ProviderProfile details) {
        Entity entity = details.getEntity();
        if (entity instanceof Person) {
            // synch full name
            Person p = (Person) entity;
            String fullname = Util.defaultString(p.getFirstName()) + " " + Util.defaultString(p.getLastName());
            p.setName(fullname.trim());
        }
        insertEntity(details.getProfileId(), details.getEnrollmentId(), entity);
    }

    /**
     * Inserts the provider contacts.
     *
     * @param details the provider profile
     */
    private void insertDesignatedContacts(ProviderProfile details) {
        List<DesignatedContact> contacts = details.getDesignatedContacts();
        if (contacts == null || contacts.isEmpty()) {
            return;
        }

        for (DesignatedContact designatedContact : contacts) {
            designatedContact.setProfileId(details.getProfileId());

            Person person = designatedContact.getPerson();
            insertNonProviderEntity(person);

            designatedContact.setId(0);
            getEm().persist(designatedContact);
        }
    }

    /**
     * Inserts the given entity (non-provider type).
     *
     * @param entity the entity to be created
     */
    private void insertNonProviderEntity(Entity entity) {
        insertEntity(0, 0, entity);
    }

    /**
     * Inserts the given entity.
     *
     * @param profileId the profile id (0 if pending enrollment)
     * @param ticketId  the ticket id (0 if not a provider)
     * @param entity    the entity to be inserted
     */
    private void insertEntity(long profileId, long ticketId, Entity entity) {
        if (entity == null) {
            return;
        }

        entity.setProfileId(profileId);
        entity.setTicketId(ticketId);

        insertContactInformation(entity.getContactInformation());

        if (entity instanceof Organization) {
            Organization org = (Organization) entity;
            insertAddress(org.getBillingAddress());
            insertAddress(org.getReimbursementAddress());
            insertAddress(org.getTen99Address());
        }

        entity.setId(0);
        getEm().persist(entity);
    }

    /**
     * Inserts the given contact information.
     *
     * @param contactInformation the information to be inserted
     */
    private void insertContactInformation(
            ContactInformation contactInformation
    ) {
        if (contactInformation == null) {
            return;
        }
        insertAddress(contactInformation.getAddress());

        contactInformation.setId(0);
        getEm().persist(contactInformation);
    }

    /**
     * Inserts the given address.
     *
     * @param address the address to be inserted
     */
    private void insertAddress(Address address) {
        if (address == null) {
            return;
        }

        address.setId(0);
        getEm().persist(address);
    }

    private void purgeChildren(Enrollment enrollment) {
        List<AcceptedAgreements> agreements = enrollment.getAgreements();
        if (agreements != null) {
            for (AcceptedAgreements acceptedAgreements : agreements) {
                getEm().remove(acceptedAgreements);
            }
        }
    }

    /**
     * Deletes the given profile from the database.
     *
     * @param profile the profile to be deleted
     */
    private void purge(ProviderProfile profile) {
        purgeChildren(profile);
        getEm().remove(profile);
    }

    private void purgeChildren(ProviderProfile profile) {
        List<ProviderService> services = profile.getServices();
        if (services != null) {
            for (ProviderService providerService : services) {
                getEm().remove(providerService);
            }
        }

        List<PayToProvider> payTos = profile.getPayToProviders();
        if (payTos != null) {
            for (PayToProvider payTo : payTos) {
                getEm().remove(payTo);
            }
        }

        OwnershipInformation ownership = profile.getOwnershipInformation();
        if (ownership != null) {
            List<BeneficialOwner> owners = ownership.getBeneficialOwners();
            if (owners != null) {
                for (BeneficialOwner owner : owners) {
                    purgeAddress(owner.getAddress());
                    purgeAddress(owner.getOtherProviderAddress());

                    getEm().remove(owner);
                }
            }

            getEm().remove(ownership);
        }

        if (profile.getStatement() != null) {
            getEm().remove(profile.getStatement());
        }

        List<Affiliation> affiliations = profile.getAffiliations();
        if (affiliations != null) {
            for (Affiliation affiliation : affiliations) {
                if (affiliation.getTargetProfileId() == 0) {
                    purgeEntity(affiliation.getEntity());
                }

                getEm().remove(affiliation);
            }
        }

        List<License> certifications = profile.getCertifications();
        if (certifications != null) {
            for (License license : certifications) {
                getEm().remove(license);
            }
        }

        List<Document> attachments = profile.getAttachments();
        if (attachments != null) {
            for (Document attachment : attachments) {
                if (attachment.getContentId() != null) {
                    getEm().remove(attachment);
                }
            }
        }

        List<DesignatedContact> designatedContacts = profile.getDesignatedContacts();
        if (designatedContacts != null) {
            for (DesignatedContact designatedContact : designatedContacts) {
                purgeEntity(designatedContact.getPerson());
                getEm().remove(designatedContact);
            }
        }
        purgeEntity(profile.getEntity());
    }

    /**
     * Deletes the given entity from the database.
     *
     * @param entity the entity to be deleted.
     */
    private void purgeEntity(Entity entity) {
        if (entity == null) {
            return;
        }

        purgeContactInformation(entity.getContactInformation());

        if (entity instanceof Organization) {
            Organization org = (Organization) entity;
            purgeAddress(org.getBillingAddress());
            purgeAddress(org.getReimbursementAddress());
            purgeAddress(org.getTen99Address());
        }

        getEm().remove(entity);
    }

    /**
     * Deletes the given address from the database.
     *
     * @param address the address to delete
     */
    private void purgeAddress(Address address) {
        if (address == null) {
            return;
        }
        getEm().remove(address);
    }

    /**
     * Deletes the given contact information from the database.
     *
     * @param contactInformation the information to delete
     */
    private void purgeContactInformation(
            ContactInformation contactInformation
    ) {
        if (contactInformation == null) {
            return;
        }
        purgeAddress(contactInformation.getAddress());
        getEm().remove(contactInformation);
    }

    /**
     * Removes the ticket details.
     *
     * @param ticketId the ticket that will be cleared
     */
    private void purgeTicketDetailsChildren(
            long profileId
    ) {
        ProviderProfile profile = getProviderDetails(profileId, true);
        if (profile != null) {
            purgeChildren(profile);
        }
    }

    /**
     * Retrieves the complex children of the given enrollment.
     *
     * @param enrollment the enrollment to be populated
     */
    private void fetchChildren(Enrollment enrollment) {
        enrollment.setAgreements(findAgreements(enrollment.getEnrollmentId()));
    }

    /**
     * Retrieves the complex children of the given profile.
     *
     * @param profile the profile to be populated
     */
    private void fetchChildren(ProviderProfile profile) {
        profile.setEntity(findEntityByProviderKey(profile.getProfileId(), profile.getEnrollmentId()));
        profile.setDesignatedContacts(findDesignatedContacts(profile.getProfileId()));
        profile.setCertifications(findCertifications(profile.getProfileId(), profile.getEnrollmentId()));
        profile.setAttachments(findAttachments(profile.getProfileId(), profile.getEnrollmentId()));
        profile.setAffiliations(findAffiliations(profile.getProfileId()));
        profile.setStatement(findStatementByProviderKey(profile.getProfileId(), profile.getEnrollmentId()));
        profile.setOwnershipInformation(findOwnershipInformation(profile.getProfileId(), profile.getEnrollmentId()));
        profile.setNotes(findNotes(profile.getProfileId(), profile.getEnrollmentId()));
        profile.setPayToProviders(findPayToProviders(profile.getProfileId(), profile.getEnrollmentId()));
        profile.setServices(findServices(profile.getProfileId(), profile.getEnrollmentId()));
        profile.setCategoriesOfServiceTypes(findCategoriesOfService(profile.getProfileId(), profile.getEnrollmentId()));
    }

    /**
     * Retrieves associated categories of service for this ticket/profile
     *
     * @param profileId the profile id
     * @param ticketId  the ticket id
     * @return the associated categories of service
     */
    @SuppressWarnings("unchecked")
    private List<ProviderCategoryOfService> findCategoriesOfService(
            long profileId,
            long ticketId
    ) {
        Query query = getEm().createQuery(
                "FROM ProviderCategoryOfService p WHERE ticketId = :ticketId AND profileId = :profileId");
        if (ticketId > 0) { // COS tickets do not populate the original profile id even on UPDATE
            query.setParameter("profileId", 0L);
            query.setParameter("ticketId", ticketId);
        } else {
            query.setParameter("ticketId", ticketId);
            query.setParameter("profileId", 0L);
        }
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<ProviderService> findServices(long profileId, long ticketId) {
        Query query = getEm().createQuery(
                "FROM ProviderService a WHERE ticketId = :ticketId AND profileId = :profileId");
        query.setParameter("profileId", profileId);
        query.setParameter("ticketId", ticketId);
        return query.getResultList();
    }

    /**
     * Retrieves the related ownership information for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related ownership information to the profile
     */
    @SuppressWarnings({"unchecked"})
    private List<PayToProvider> findPayToProviders(
            long profileId,
            long ticketId
    ) {
        Query query = getEm().createQuery("FROM PayToProvider o WHERE ticketId = :ticketId AND profileId = :profileId");
        query.setParameter("profileId", profileId);
        query.setParameter("ticketId", ticketId);
        return query.getResultList();
    }

    /**
     * Retrieves the related ownership information for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related ownership information to the profile
     */
    @SuppressWarnings("rawtypes")
    private OwnershipInformation findOwnershipInformation(
            long profileId,
            long ticketId
    ) {
        Query query = getEm().createQuery(
                "FROM OwnershipInformation o WHERE ticketId = :ticketId AND profileId = :profileId");
        query.setParameter("profileId", profileId);
        query.setParameter("ticketId", ticketId);
        List rs = query.getResultList();
        if (rs.isEmpty()) {
            return null;
        }

        if (rs.size() > 1) {
            throw new IllegalStateException("Multiple ownership information records were found!");
        }
        return (OwnershipInformation) rs.get(0);
    }

    /**
     * Retrieves the related agreements for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related agreements to the profile
     */
    @SuppressWarnings("unchecked")
    private List<AcceptedAgreements> findAgreements(
            long ticketId
    ) {
        Query query = getEm().createQuery(
                "FROM AcceptedAgreements a WHERE ticketId = :ticketId");
        query.setParameter("ticketId", ticketId);
        return query.getResultList();
    }

    /**
     * Retrieves the related notes for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related notes to the profile
     */
    @SuppressWarnings("unchecked")
    private List<Note> findNotes(long profileId, long ticketId) {
        Query query = getEm().createQuery("FROM Note a WHERE ticketId = :ticketId AND profileId = :profileId");
        query.setParameter("profileId", profileId);
        query.setParameter("ticketId", ticketId);
        return query.getResultList();
    }

    /**
     * Retrieves the related provider statement for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related statement to the profile
     */
    @SuppressWarnings("rawtypes")
    private ProviderStatement findStatementByProviderKey(
            long profileId,
            long ticketId
    ) {
        Query query = getEm().createQuery(
                "FROM ProviderStatement ps WHERE ticketId = :ticketId AND profileId = :profileId");
        query.setParameter("profileId", profileId);
        query.setParameter("ticketId", ticketId);
        List rs = query.getResultList();
        if (rs.isEmpty()) {
            return null;
        }
        return (ProviderStatement) rs.get(0);
    }

    /**
     * Retrieves the related affiliations for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related affiliations to the profile
     */
    @SuppressWarnings("unchecked")
    private List<Affiliation> findAffiliations(long profileId) {
        Query query = getEm().createQuery("FROM Affiliation a WHERE profileId = :profileId");
        query.setParameter("profileId", profileId);

        List<Affiliation> affiliations = query.getResultList();
        for (Affiliation affiliation : affiliations) {
            if (affiliation.getTargetEntityId() > 0) {
                affiliation.setEntity(getEm().find(Entity.class, affiliation.getTargetEntityId()));
                affiliation.setAffiliateLicenses(findAffiliateLicences(affiliation.getTargetEntityId()));
            } else {
                affiliation.setEntity(findEntityByProviderKey(affiliation.getTargetProfileId(), 0L));
            }
        }
        return affiliations;
    }

    @SuppressWarnings("unchecked")
    private List<License> findAffiliateLicences(long affiliateId) {
        Query query = getEm().createQuery("FROM License l WHERE affiliateId = :affiliateId");
        query.setParameter("affiliateId", affiliateId);
        return query.getResultList();
    }

    /**
     * Retrieves the related certifications for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related certifications to the profile
     */
    @SuppressWarnings("unchecked")
    private List<License> findCertifications(long profileId, long ticketId) {
        Query query = getEm().createQuery("FROM License l WHERE ticketId = :ticketId AND profileId = :profileId");
        query.setParameter("profileId", profileId);
        query.setParameter("ticketId", ticketId);
        return query.getResultList();
    }

    /**
     * Retrieves the related contacts for the given profile key.
     *
     * @param profileId the profile id of the provider
     * @param ticketId  the request ticket id
     * @return the related contacts to the profile
     */
    @SuppressWarnings("unchecked")
    private List<DesignatedContact> findDesignatedContacts(
            long profileId
    ) {
        Query query = getEm().createQuery(
                "FROM DesignatedContact d WHERE profileId = :profileId");
        query.setParameter("profileId", profileId);
        return query.getResultList();
    }

    /**
     * Appends the sorting criteria.
     *
     * @param fetchQuery the fetch query
     * @param criteria   the criteria to append
     */
    private void appendSorting(
            StringBuilder fetchQuery,
            SearchCriteria criteria
    ) {
        if (Util.isNotBlank(criteria.getSortColumn())) {
            fetchQuery.append(" ORDER BY ").append(criteria.getSortColumn())
                    .append(criteria.isAscending() ? " ASC" : " DESC");
        }
    }

    /**
     * Saves the attachment blob contents.
     *
     * @param content the content to be saved
     * @return the content id
     * @throws IOException     if the stream cannot be saved
     * @throws SQLException    if content cannot be transformed to a blob
     * @throws SerialException if content cannot be transformed to a blob
     */
    private String saveAttachmentContent(InputStream content) throws IOException, SerialException, SQLException {
        BinaryContent binaryContent = new BinaryContent();
        binaryContent.setContent(new SerialBlob(IOUtils.toByteArray(content)));
        getEm().persist(binaryContent);
        return binaryContent.getContentId();
    }

    /**
     * Checks the sort column parameter for valid input.
     *
     * @param criteria   the criteria used for search
     * @param maxColumns the maximum number allowed as sort column
     */
    private void checkSortColumn(SearchCriteria criteria, int maxColumns) {
        if (Util.isNotBlank(criteria.getSortColumn())) {
            if (!Util.isDigits(criteria.getSortColumn())) {
                throw new IllegalArgumentException("Invalid sort column specified.");
            }
            int column = Integer.parseInt(criteria.getSortColumn());
            if (column < 1 || column > maxColumns) {
                throw new IllegalArgumentException("Invalid sort column specified.");
            }
        }
    }

    /**
     * Appends the provider search criteria to the current buffer.
     *
     * @param buffer   the query buffer
     * @param user     the current user
     * @param criteria the search criteria
     */
    private void appendCriteria(
            StringBuilder buffer,
            CMSUser user,
            ProviderSearchCriteria criteria
    ) {
        if (!FULL_ACCESS.contains(user.getRole().getDescription())) {
            buffer.append("AND t.createdBy = :user ");
        }
        if (criteria.getProfileId() > 0) {
            buffer.append("AND e.profileId = :profileId ");
        }
        if (Util.isNotBlank(criteria.getProviderName())) {
            buffer.append("AND LOCATE(:name, UPPER(e.name)) > 0");
        }
        if (Util.isNotBlank(criteria.getNpi())) {
            buffer.append("AND e.npi = :npi ");
        }
        if (Util.isNotBlank(criteria.getRiskLevel())) {
            if ("NULL".equals(criteria.getRiskLevel())) {
                buffer.append("AND rl.description IS NULL ");
            } else {
                buffer.append("AND rl.description = :riskLevel ");
            }
        } else {
            if (criteria.getRiskLevels() != null && !criteria.getRiskLevels().isEmpty()) {
                buffer.append("AND rl.description in (:riskLevel) ");
            }
        }
        if (Util.isNotBlank(criteria.getEnrollmentNumber())) {
            buffer.append("AND t.enrollmentId = :enrollmentNumber ");
        }
        if (Util.isNotBlank(criteria.getProviderType())) {
            buffer.append("AND pt.description = :providerType ");
        }
        if (Util.isNotEmpty(criteria.getRequestTypes())) {
            buffer.append("AND rt.description in (:requestTypes) ");
        }
        if (Util.isNotEmpty(criteria.getStatuses())) {
            buffer.append("AND ts.description in (:enrollmentStatuses) ");
        }
        if (criteria.getSubmissionDateStart() != null) {
            buffer.append("AND t.submissionDate >= :submissionDateStart ");
        }
        if (criteria.getSubmissionDateEnd() != null) {
            buffer.append("AND t.submissionDate <= :submissionDateEnd ");
        }
        if (criteria.getStatusDateStart() != null) {
            buffer.append("AND t.statusDate >= :statusDateStart ");
        }
        if (criteria.getStatusDateEnd() != null) {
            buffer.append("AND t.statusDate <= :statusDateEnd ");
        }
        if (criteria.getCreateDateStart() != null) {
            buffer.append("AND t.createdOn >= :createDateStart ");
        }
        if (criteria.getCreateDateEnd() != null) {
            buffer.append("AND t.createdOn <= :createDateEnd ");
        }
    }

    /**
     * Appends the enrollment search criteria to the current buffer.
     *
     * @param buffer   the query buffer
     * @param criteria the search criteria
     */
    private void appendCriteria(
            StringBuilder buffer,
            EnrollmentSearchCriteria criteria
    ) {
        if (criteria.getCreateDateStart() != null) {
            buffer.append("AND t.createdOn >= :createDateStart ");
        }
        if (criteria.getCreateDateEnd() != null) {
            buffer.append("AND t.createdOn <= :createDateEnd ");
        }
        if (Util.isNotEmpty(criteria.getProviderTypes())) {
            buffer.append("AND pt.code in (:providerTypes) ");
        }
    }

    /**
     * Binds the provider search criteria to the query.
     *
     * @param query    the query to bind to
     * @param user     the user performing the action
     * @param criteria the search criteria
     */
    private void bindParameters(
            Query query,
            CMSUser user,
            ProviderSearchCriteria criteria
    ) {
        if (!FULL_ACCESS.contains(user.getRole().getDescription())) {
            query.setParameter("user", user);
        }
        if (criteria.getProfileId() > 0) {
            query.setParameter("profileId", criteria.getProfileId());
        }
        if (Util.isNotBlank(criteria.getNpi())) {
            query.setParameter("npi", criteria.getNpi());
        }
        if (Util.isNotBlank(criteria.getProviderName())) {
            query.setParameter("name", criteria.getProviderName().toUpperCase());
        }
        if (Util.isNotBlank(criteria.getRiskLevel())) {
            if (!"NULL".equals(criteria.getRiskLevel())) {
                query.setParameter("riskLevel", criteria.getRiskLevel());
            }
        } else {
            if (criteria.getRiskLevels() != null && !criteria.getRiskLevels().isEmpty()) {
                query.setParameter("riskLevel", criteria.getRiskLevels());
            }
        }

        if (Util.isNotBlank(criteria.getEnrollmentNumber())) {
            query.setParameter("enrollmentNumber", Long.parseLong(criteria.getEnrollmentNumber()));
        }
        if (Util.isNotBlank(criteria.getProviderType())) {
            query.setParameter("providerType", criteria.getProviderType());
        }
        if (Util.isNotEmpty(criteria.getRequestTypes())) {
            query.setParameter("requestTypes", criteria.getRequestTypes());
        }
        if (Util.isNotEmpty(criteria.getStatuses())) {
            query.setParameter("enrollmentStatuses", criteria.getStatuses());
        }
        if (criteria.getSubmissionDateStart() != null) {
            query.setParameter("submissionDateStart", criteria.getSubmissionDateStart());
        }
        if (criteria.getSubmissionDateEnd() != null) {
            query.setParameter("submissionDateEnd", criteria.getSubmissionDateEnd());
        }
        if (criteria.getStatusDateStart() != null) {
            query.setParameter("statusDateStart", criteria.getStatusDateStart());
        }
        if (criteria.getStatusDateEnd() != null) {
            query.setParameter("statusDateEnd", criteria.getStatusDateEnd());
        }
        if (criteria.getCreateDateStart() != null) {
            query.setParameter("createDateStart", criteria.getCreateDateStart());
        }
        if (criteria.getCreateDateEnd() != null) {
            query.setParameter("createDateEnd", criteria.getCreateDateEnd());
        }
    }

    /**
     * Binds the enrollment search criteria to the query.
     *
     * @param query    the query to bind to
     * @param criteria the search criteria
     */
    private void bindParameters(
            Query query,
            EnrollmentSearchCriteria criteria
    ) {
        if (criteria.getCreateDateStart() != null) {
            query.setParameter("createDateStart", criteria.getCreateDateStart());
        }
        if (criteria.getCreateDateEnd() != null) {
            query.setParameter("createDateEnd", criteria.getCreateDateEnd());
        }
        if (Util.isNotEmpty(criteria.getProviderTypes())) {
            query.setParameter("providerTypes", criteria.getProviderTypes());
        }
    }

    /**
     * Appends the provider search criteria to the current buffer.
     *
     * @param buffer   the query buffer
     * @param criteria the search criteria
     */
    private void appendCriteria(
            StringBuilder buffer,
            PracticeSearchCriteria criteria
    ) {
        if (criteria.isAgency()) {
            buffer.append("AND e.agencyId IS NOT NULL");
        }

        if (criteria.getProfileId() > 0) {
            buffer.append("AND e.profileId = :profileId");
        }

        if (Util.isNotBlank(criteria.getName())) {
            buffer.append("AND LOCATE(:name, UPPER(e.name)) > 0");
        }
        if (Util.isNotBlank(criteria.getNpi())) {
            buffer.append("AND e.npi = :npi ");
        }
        if (Util.isNotBlank(criteria.getCity())) {
            buffer.append("AND LOCATE(:city, UPPER(ci.address.city)) > 0");
        }
        if (Util.isNotBlank(criteria.getState())) {
            buffer.append("AND ci.address.state = :state ");
        }
        if (Util.isNotBlank(criteria.getZip())) {
            buffer.append("AND ci.address.zipcode = :zip ");
        }
    }

    /**
     * Binds the provider search criteria to the query.
     *
     * @param query    the query to bind to
     * @param criteria the search criteria
     */
    private void bindParameters(
            Query query,
            PracticeSearchCriteria criteria
    ) {
        if (criteria.getProfileId() > 0) {
            query.setParameter("profileId", criteria.getProfileId());
        }

        if (Util.isNotBlank(criteria.getName())) {
            query.setParameter("name", criteria.getName().toUpperCase());
        }
        if (Util.isNotBlank(criteria.getNpi())) {
            query.setParameter("npi", criteria.getNpi());
        }
        if (Util.isNotBlank(criteria.getCity())) {
            query.setParameter("city", criteria.getCity());
        }
        if (Util.isNotBlank(criteria.getState())) {
            query.setParameter("state", criteria.getState());
        }
        if (Util.isNotBlank(criteria.getZip())) {
            query.setParameter("zip", criteria.getZip());
        }
    }

    /**
     * This copies all cos attached to the ticket into the profile.
     *
     * @param profileId the profile associated to the cos
     * @param ticketId  the ticket associated
     * @throws PortalServiceException if any error occurs
     */
    private void promoteCOSToBase(
            CMSUser user,
            long profileId,
            long ticketId
    ) throws PortalServiceException {
        List<ProviderCategoryOfService> services = new ArrayList<>();
        services.addAll(getPendingCategoryOfServices(user, ticketId));
        services.addAll(getProviderCategoryOfServices(user, profileId));
        for (ProviderCategoryOfService service : services) {
            service.setProfileId(profileId);
            service.setTicketId(0);
            getEm().merge(service);
        }
    }

    /**
     * This copies all notes attached to the ticket into the profile.
     *
     * @param profileId the profile associated to the note
     * @param ticketId  the ticket associated
     */
    private void promoteNotesToBase(long profileId, long ticketId) {
        List<Note> pendingNotes = new ArrayList<>();
        pendingNotes.addAll(findNotes(0, ticketId));
        pendingNotes.addAll(findNotes(profileId, ticketId));

        for (Note note : pendingNotes) {
            note.setProfileId(profileId);
            note.setTicketId(0);
            getEm().merge(note);
        }
    }

    /**
     * Inserts the given note.
     *
     * @param user      the user creating the note
     * @param profileId the profile to attach it to
     * @param ticketId  the ticket to attach it to
     * @param text      the note text
     */
    private void insertNote(
            CMSUser user,
            long profileId,
            long ticketId,
            String text
    ) {
        Note n = new Note();
        n.setProfileId(profileId);
        n.setTicketId(ticketId);
        n.setText(text);
        n.setCreatedBy(user.getUserId());
        n.setCreatedOn(Calendar.getInstance().getTime());
        n.setId(0);
        getEm().persist(n);
    }

    /**
     * Creates or updates the given ticket.
     *
     * @param user          the user performing the operation
     * @param ticket        the ticket to be saved
     * @param insertDetails if true, also reinserts the details
     * @return the ticket id
     * @throws PortalServiceException for any errors encountered
     */
    private long saveTicket(
            CMSUser user,
            Enrollment enrollment,
            boolean insertDetails
    ) throws PortalServiceException {
        enrollment.setLastUpdatedBy(user);
        ProviderProfile details = enrollment.getDetails();
        if (details.getProfileId() != 0) {
            enrollment.setProfileReferenceId(details.getProfileId());
        }

        Enrollment dbEnrollment = getEm().merge(enrollment);

        fetchChildren(dbEnrollment);
        purgeChildren(dbEnrollment);

        enrollment.setEnrollmentId(dbEnrollment.getEnrollmentId());
        saveRelatedEntities(enrollment.clone());

        if (insertDetails) {
            insertProfile(dbEnrollment.getEnrollmentId(), details);
            dbEnrollment.setProfileReferenceId(details.getProfileId());

            getEm().merge(dbEnrollment);
        } else {
            // Currently the system manually handles persisting underlying relationships
            // of the provider profile, so to prevent hibernate from complaining that we're
            // changing IDs out from under it, we clone, delete, readd to update them
            //
            // We also want to ensure that the enrollment currently being saved is the one the
            // with which provider profile associates
            details.setEnrollmentId(dbEnrollment.getEnrollmentId());
            getEm().merge(details);
            ProviderProfile profile = details.clone();
            purgeTicketDetailsChildren(dbEnrollment.getProfileReferenceId());
            saveRelatedEntities(profile);
            dbEnrollment.setDetails(profile);
        }

        return dbEnrollment.getEnrollmentId();
    }

    @Override
    public Document findAttachment(
            CMSUser user,
            long attachmentId
    ) throws PortalServiceException {
        Document attachment = getEm().find(Document.class, attachmentId);
        if (attachment != null) {
            if (attachment.getTicketId() > 0) {
                checkTicketEntitlement(user, attachment.getTicketId());
            } else if (attachment.getProfileId() > 0) {
                checkProfileEntitlement(user, attachment.getProfileId());
            } else {
                if (!attachment.getCreatedBy().equals(user.getUserId())) {
                    throw new PortalServiceException("Access Denied.");
                }
            }
        }

        return attachment;
    }

    /**
     * Checks the validity status of the ticket.
     *
     * @param ticketId  the ticket id to check
     * @param profileId the profile to check
     * @return the validity status of the ticket
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public Validity getSubmissionValidity(
            long ticketId,
            long profileId
    ) throws PortalServiceException {
        Enrollment ticket = getEm().find(Enrollment.class, ticketId);
        if (ticket == null) {
            throw new PortalServiceException("No such ticket.");
        }

        if (ViewStatics.UPDATE_REQUEST.equals(ticket.getRequestType().getDescription())
                || ViewStatics.RENEWAL_REQUEST.equals(ticket.getRequestType().getDescription())) {

            // updates and renewals should have a reference to an existing approved profile
            ProviderProfile baseProfile = getProviderDetails(profileId, false);
            if (baseProfile == null) {
                throw new PortalServiceException("No such profile.");
            }

            if (profileHasPendingSubmission(profileId)) {
                return Validity.SUPERSEDED;
            }

            Date referenceTimestamp = ticket.getReferenceTimestamp();
            if (referenceTimestamp != null) {
                if (baseProfile.getModifiedOn().after(referenceTimestamp)) {
                    return Validity.STALE;
                }
            }
        }
        // new enrollments are always valid for submission
        return Validity.VALID;
    }

    private boolean profileHasPendingSubmission(long profileId) {
        String query = "SELECT count(*) " +
                "FROM ProviderProfile p, " +
                "Enrollment t " +
                "LEFT JOIN t.status ts, " +
                "Entity e " +
                "WHERE p.enrollmentId = t.enrollmentId " +
                "AND e.ticketId = p.enrollmentId " +
                "AND p.profileId = e.profileId " +
                "AND p.enrollmentId > 0 " +
                "AND e.profileId = :profileId " +
                "AND ts.description = :enrollmentStatus";

        int pendingSubmissions = getEm()
                .createQuery(query, Number.class)
                .setParameter("profileId", profileId)
                .setParameter("enrollmentStatus", ViewStatics.PENDING_STATUS)
                .getSingleResult()
                .intValue();
        return pendingSubmissions > 0;
    }

    @Override
    public List<Note> findNotes(long ticketId) {
        List<Note> pendingNotes = new ArrayList<>();
        pendingNotes.addAll(findNotes(0, ticketId));
        return pendingNotes;
    }

    /**
     * Performs direct update to the data during the approval process.
     *
     * @throws PortalServiceException
     */
    @Override
    public Enrollment saveEnrollmentDetails(
            Enrollment enrollment
    ) throws PortalServiceException {
        Enrollment ticket = getEnrollment(enrollment.getEnrollmentId(), null).get();
        ProviderProfile updatedProfile = enrollment.getDetails().clone();

        ProviderProfile dbProfile = getProviderDetails(ticket.getProfileReferenceId(), true);

        purgeChildren(ticket);
        saveRelatedEntities(enrollment.clone());

        if (dbProfile != null) {
            purgeChildren(dbProfile);
            updatedProfile.setProfileId(dbProfile.getProfileId());
            updatedProfile.setEnrollmentId(enrollment.getEnrollmentId());
            getEm().merge(updatedProfile);

            saveRelatedEntities(updatedProfile);
        } else {
            insertProfile(ticket.getEnrollmentId(), updatedProfile);
        }

        ticket.setDetails(updatedProfile);

        return ticket;
    }

    /**
     * Renews the profiles directly, without making any data changes.
     *
     * @param user       the current user
     * @param profileIds the profiles to renew
     */
    @Override
    public Long[] renewalProfiles(
            CMSUser user,
            Set<Long> profileIds
    ) {
        List<Long> results = new ArrayList<>();
        for (Long profileId : profileIds) {
            try {
                Enrollment ticket = renewProfile(user, profileId);
                long ticketId = saveAsDraft(user, ticket);
                results.add(ticketId);
            } catch (PortalServiceException e) {
                results.add(0L);
            }
        }
        return results.toArray(new Long[0]);
    }

    /**
     * Gets the COS associated with a profile.
     *
     * @param user      CMS user
     * @param profileId profile id.
     * @return the list of services
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public List<ProviderCategoryOfService> getProviderCategoryOfServices(
            CMSUser user,
            long profileId
    ) throws PortalServiceException {
        checkProfileEntitlement(user, profileId);
        return queryCategoriesOfService("p.profileId = :id")
                .setParameter("id", profileId)
                .getResultList();
    }

    private TypedQuery<ProviderCategoryOfService> queryCategoriesOfService(
            String condition
    ) {
        EntityGraph graph = getEm()
                .getEntityGraph("ProviderCategoryOfService with categories");
        return getEm()
                .createQuery("SELECT DISTINCT p " +
                                "FROM ProviderCategoryOfService p " +
                                "WHERE " + condition + " " +
                                "ORDER BY p.startDate",
                        ProviderCategoryOfService.class)
                .setHint("javax.persistence.loadgraph", graph);
    }

    /**
     * Adds COS to the profile.
     *
     * @param user              the user performing the action
     * @param categoryOfService the entity to persist
     * @param prevCatServiceId  if last COS needs an update in end date this will be provided
     * @param prevCatEndDate    last COS end date
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void addCOSToProfile(
            CMSUser user,
            ProviderCategoryOfService categoryOfService,
            long prevCatServiceId,
            Date prevCatEndDate
    ) throws PortalServiceException {
        checkProfileEntitlement(user, categoryOfService.getProfileId());
        categoryOfService.setId(0);
        getEm().persist(categoryOfService);
        if (prevCatServiceId != 0) {
            ProviderCategoryOfService service = getEm().find(ProviderCategoryOfService.class, prevCatServiceId);
            if (service != null) {
                service.setEndDate(prevCatEndDate);
                getEm().merge(service);
            }
        }
    }

    /**
     * Deletes the COS.
     *
     * @param user      the user performing the action
     * @param profileId the profile id
     * @param id        the cos id
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void deleteCOSByProfile(
            CMSUser user,
            long profileId,
            long id
    ) throws PortalServiceException {
        checkProfileEntitlement(user, profileId);
        ProviderCategoryOfService service = getEm().find(ProviderCategoryOfService.class, id);
        if (service != null) {
            getEm().remove(service);
        }
    }

    /**
     * Adds COS to the ticket.
     *
     * @param user              the user performing the action
     * @param categoryOfService the entity to persist
     * @param prevCatServiceId  if last COS needs an update in end date this will be provided
     * @param prevCatEndDate    last COS end date
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void addCOSToTicket(
            CMSUser user,
            ProviderCategoryOfService categoryOfService,
            long prevCatServiceId,
            Date prevCatEndDate
    ) throws PortalServiceException {
        checkTicketEntitlement(user, categoryOfService.getTicketId());
        categoryOfService.setId(0);
        getEm().persist(categoryOfService);
        if (prevCatServiceId != 0) {
            ProviderCategoryOfService service = getEm().find(ProviderCategoryOfService.class, prevCatServiceId);
            if (service != null) {
                service.setEndDate(prevCatEndDate);
                getEm().merge(service);
            }
        }
    }

    /**
     * Gets the COS associated with a ticket.
     *
     * @param user     CMS user
     * @param ticketId ticket id.
     * @return the list of services
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public List<ProviderCategoryOfService> getPendingCategoryOfServices(
            CMSUser user,
            long ticketId
    ) throws PortalServiceException {
        checkTicketEntitlement(user, ticketId);
        return queryCategoriesOfService("ticketId = :id")
                .setParameter("id", ticketId)
                .getResultList();
    }

    /**
     * Deletes the COS by ticket.
     *
     * @param user     the user performing the action
     * @param ticketId the ticket id
     * @param id       the cos id
     * @throws PortalServiceException for any errors encountered
     */
    @Override
    public void deleteCOSByTicket(
            CMSUser user,
            long ticketId,
            long id
    ) throws PortalServiceException {
        checkTicketEntitlement(user, ticketId);
        ProviderCategoryOfService service = getEm().find(ProviderCategoryOfService.class, id);
        if (service != null) {
            getEm().remove(service);
        }
    }

    /**
     * Returns true if the first parameter can be considered an employer of the
     * second.
     *
     * @param externalUserId the employer to be checked
     * @param profileNPI     the employee to be checked
     * @return true if there is an affiliation between the two arguments that
     * gives the first access to the latter
     */
    @SuppressWarnings("unchecked")
    @Override
    public boolean hasGroupAffiliation(
            String externalUserId,
            String profileNPI
    ) {
        String fetchQuery = "SELECT NEW gov.medicaid.entities.ProfileHeader(e.profileId, e.npi, pt.description, "
                + "p.effectiveDate, p.modifiedOn) FROM ProviderProfile p, Entity e LEFT JOIN e.providerType pt "
                + "WHERE e.ticketId = p.enrollmentId AND p.profileId = e.profileId and e.npi = :npi "
                + "AND p.enrollmentId = 0 ORDER BY 5 DESC";

        Query items = getEm().createQuery(fetchQuery);
        items.setParameter("npi", profileNPI);
        List<ProfileHeader> profiles = items.getResultList();

        // for all profiles with NPI = requested profile (shouldn't be that many, likely 1 or 0
        for (ProfileHeader profileHeader : profiles) {

            // find all practice locations
            List<Affiliation> affiliations = findAffiliations(profileHeader.getProfileId());

            // for every practice location
            for (Affiliation affiliation : affiliations) {

                if (ViewStatics.DISCRIMINATOR_LOCATION.equals(affiliation.getObjectType())) {
                    // check if the external user has the same NPI
                    if (affiliation.getEntity().getNpi().equals(externalUserId)) {
                        return true;
                    }
                }
            }
        }

        // no employer relationship found
        return false;
    }

    /**
     * Returns true if there is a profile found in the database with the given
     * NPI.
     *
     * @param profileNPI the NPI to be checked
     * @return true if a record matches
     */
    @Override
    public boolean existsProfile(String profileNPI) {
        String fetchQuery = "SELECT NEW gov.medicaid.entities.ProfileHeader(e.profileId, e.npi, pt.description, "
                + "p.effectiveDate, p.modifiedOn) FROM ProviderProfile p, Entity e LEFT JOIN e.providerType pt "
                + "WHERE e.ticketId = p.enrollmentId AND p.profileId = e.profileId and e.npi = :npi "
                + "AND p.enrollmentId = 0 ORDER BY 5 DESC";

        Query items = getEm().createQuery(fetchQuery);
        items.setParameter("npi", profileNPI);
        return !items.getResultList().isEmpty();
    }
}
