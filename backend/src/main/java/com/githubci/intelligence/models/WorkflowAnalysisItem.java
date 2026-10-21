package com.githubci.intelligence.models;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class WorkflowAnalysisItem {

    private String pk;
    private String sk;
    private String failurePk;
    private String failureSk;
    private String repository;
    private String workflowRun;
    private String analysis;
    private String status;
    private String processingToken;
    private String createdAt;
    private String updatedAt;
    private String leaseUntil;
    private String runSk;
    private String repositoryDetails;

    @DynamoDbPartitionKey
    @DynamoDbSecondaryPartitionKey(indexNames = "repository-runs")
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    @DynamoDbSecondaryPartitionKey(indexNames = "recent-failures")
    public String getFailurePk() { return failurePk; }
    public void setFailurePk(String failurePk) { this.failurePk = failurePk; }

    @DynamoDbSecondarySortKey(indexNames = "recent-failures")
    public String getFailureSk() { return failureSk; }
    public void setFailureSk(String failureSk) { this.failureSk = failureSk; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getWorkflowRun() { return workflowRun; }
    public void setWorkflowRun(String workflowRun) { this.workflowRun = workflowRun; }

    public String getAnalysis() { return analysis; }
    public void setAnalysis(String analysis) { this.analysis = analysis; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getProcessingToken() { return processingToken; }
    public void setProcessingToken(String processingToken) { this.processingToken = processingToken; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(String leaseUntil) { this.leaseUntil = leaseUntil; }

    @DynamoDbSecondarySortKey(indexNames = "repository-runs")
    public String getRunSk() { return runSk; }
    public void setRunSk(String runSk) { this.runSk = runSk; }

    public String getRepositoryDetails() { return repositoryDetails; }
    public void setRepositoryDetails(String repositoryDetails) { this.repositoryDetails = repositoryDetails; }
}
