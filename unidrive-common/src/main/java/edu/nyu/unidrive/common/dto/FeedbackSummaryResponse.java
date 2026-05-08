package edu.nyu.unidrive.common.dto;

public final class FeedbackSummaryResponse {

    private final String feedbackId;
    private final String submissionId;
    private final String term;
    private final String course;
    private final String assignmentId;
    private final String studentId;
    private final String fileName;
    private final String sha256;

    public FeedbackSummaryResponse(
        String feedbackId,
        String submissionId,
        String term,
        String course,
        String assignmentId,
        String studentId,
        String fileName,
        String sha256
    ) {
        this.feedbackId = feedbackId;
        this.submissionId = submissionId;
        this.term = term;
        this.course = course;
        this.assignmentId = assignmentId;
        this.studentId = studentId;
        this.fileName = fileName;
        this.sha256 = sha256;
    }

    public String getFeedbackId() {
        return feedbackId;
    }

    public String getSubmissionId() {
        return submissionId;
    }

    public String getTerm() {
        return term;
    }

    public String getCourse() {
        return course;
    }

    public String getAssignmentId() {
        return assignmentId;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSha256() {
        return sha256;
    }
}
