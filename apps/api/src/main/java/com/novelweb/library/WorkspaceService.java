package com.novelweb.library;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class WorkspaceService {

    private final WorkspaceRepository repository;

    public WorkspaceService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    public WorkspaceRepository.WorkspaceRecord createWorkspace(UUID actorUserId, String requestedName) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        return repository.createWorkspace(actorUserId, requiredText(requestedName, "name", 120));
    }

    public WorkspaceRepository.MemberRecord addMember(
            UUID actorUserId, UUID workspaceId, UUID memberUserId, WorkspaceRepository.Role role) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        Objects.requireNonNull(workspaceId, "workspace id is required");
        Objects.requireNonNull(memberUserId, "member user id is required");
        return repository.addMember(actorUserId, workspaceId, memberUserId, Objects.requireNonNull(role));
    }

    public WorkspaceRepository.MemberRecord changeMemberRole(
            UUID actorUserId, UUID workspaceId, UUID memberUserId, WorkspaceRepository.Role role) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        Objects.requireNonNull(workspaceId, "workspace id is required");
        Objects.requireNonNull(memberUserId, "member user id is required");
        return repository.changeMemberRole(actorUserId, workspaceId, memberUserId, Objects.requireNonNull(role));
    }

    public WorkspaceRepository.WorkspaceRecord transferOwnership(
            UUID actorUserId, UUID workspaceId, UUID newOwnerUserId) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        Objects.requireNonNull(workspaceId, "workspace id is required");
        return repository.transferOwnership(actorUserId, workspaceId, Objects.requireNonNull(newOwnerUserId));
    }

    public WorkspaceRepository.MemberRecord revokeMember(
            UUID actorUserId, UUID workspaceId, UUID memberUserId) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        Objects.requireNonNull(workspaceId, "workspace id is required");
        return repository.revokeMember(actorUserId, workspaceId, Objects.requireNonNull(memberUserId));
    }

    public Optional<WorkspaceRepository.WorkspaceRecord> findWorkspace(UUID actorUserId, UUID workspaceId) {
        return repository.findWorkspace(Objects.requireNonNull(actorUserId), Objects.requireNonNull(workspaceId));
    }

    public List<WorkspaceRepository.MemberRecord> listMembers(UUID actorUserId, UUID workspaceId) {
        return repository.listMembers(Objects.requireNonNull(actorUserId), Objects.requireNonNull(workspaceId));
    }

    private static String requiredText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
