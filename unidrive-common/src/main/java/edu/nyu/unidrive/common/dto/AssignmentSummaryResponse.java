package edu.nyu.unidrive.common.dto;

public final class AssignmentSummaryResponse {

    private final String assignmentId;
    private final String term;
    private final String course;
    private final String title;
    private final String fileName;
    private final String sha256;
    private final String deadline;

    public AssignmentSummaryResponse(String assignmentId, String title, String fileName, String sha256) {
        this(assignmentId, null, null, title, fileName, sha256, null);
    }

    public AssignmentSummaryResponse(
        String assignmentId,
        String term,
        String course,
        String title,
        String fileName,
        String sha256
    ) {
        this(assignmentId, term, course, title, fileName, sha256, null);
    }

    public AssignmentSummaryResponse(
        String assignmentId,
        String term,
        String course,
        String title,
        String fileName,
        String sha256,
        String deadline
    ) {
        this.assignmentId = assignmentId;
        this.term = term;
        this.course = course;
        this.title = title;
        this.fileName = fileName;
        this.sha256 = sha256;
        this.deadline = deadline;
    }

    public String getAssignmentId() {
        return assignmentId;
    }

    public String getTerm() {
        return term;
    }

    public String getCourse() {
        return course;
    }

    public String getTitle() {
        return title;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSha256() {
        return sha256;
    }

    public String getDeadline() {
        return deadline;
    }
}
