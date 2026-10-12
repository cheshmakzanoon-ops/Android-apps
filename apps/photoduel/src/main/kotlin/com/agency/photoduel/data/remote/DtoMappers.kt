package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.BlockedUser
import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeResult
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.CreatedInvitation
import com.agency.photoduel.domain.model.HistoryEntry
import com.agency.photoduel.domain.model.InvitationPreview
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.model.MyStatus
import com.agency.photoduel.domain.model.Page
import com.agency.photoduel.domain.model.Prompt
import com.agency.photoduel.domain.model.PromptCatalog
import com.agency.photoduel.domain.model.RecoveryCode
import com.agency.photoduel.domain.model.RoomDetail
import com.agency.photoduel.domain.model.RoomMember
import com.agency.photoduel.domain.model.RoomSummary
import com.agency.photoduel.domain.model.Session
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.model.Tally
import com.agency.photoduel.domain.model.UserProfile

// Wire DTO to frozen domain model. An enum name the contract does not list throws
// IllegalArgumentException, which the client reports as an unreadable response (ErrorCode.UNKNOWN).

private inline fun <reified E : Enum<E>> contractEnum(value: String): E = enumValueOf(value)

internal fun UserDto.toDomain() = UserProfile(id = id, displayName = displayName, hasRecoveryCode = hasRecoveryCode)

internal fun SessionResponseDto.toDomain() =
    Session(user = user.toDomain(), token = session.token, expiresAtEpochMillis = session.expiresAtEpochMillis)

internal fun RecoveryCodeDto.toDomain() = RecoveryCode(recoveryCode)

internal fun PromptDto.toDomain() = Prompt(id = id, title = title, brief = brief)

internal fun PromptCatalogDto.toDomain() = PromptCatalog(version = version, prompts = prompts.map { it.toDomain() })

internal fun RoomSummaryDto.toDomain() = RoomSummary(
    id = id,
    name = name,
    memberCount = memberCount,
    capacity = capacity,
    activeChallengeState = activeChallengeState?.let { contractEnum<ChallengeState>(it) },
)

internal fun RoomMemberDto.toDomain() =
    RoomMember(userId = userId, displayName = displayName, role = contractEnum<MemberRole>(role))

internal fun RoomDetailDto.toDomain() = RoomDetail(
    id = id,
    name = name,
    capacity = capacity,
    members = members.map { it.toDomain() },
    viewerRole = contractEnum<MemberRole>(viewerRole),
    viewerUserId = viewerUserId,
)

internal fun CreatedInvitationDto.toDomain() =
    CreatedInvitation(id = id, token = token, expiresAtEpochMillis = expiresAtEpochMillis)

internal fun InvitationPreviewDto.toDomain() = InvitationPreview(
    roomName = roomName,
    ownerDisplayName = ownerDisplayName,
    memberCount = memberCount,
    capacity = capacity,
    expiresAtEpochMillis = expiresAtEpochMillis,
)

internal fun ChallengeDto.toDomain() = Challenge(
    id = id,
    roomId = roomId,
    roundNumber = roundNumber,
    prompt = prompt.toDomain(),
    state = contractEnum<ChallengeState>(state),
    outcome = outcome?.let { contractEnum<ChallengeOutcome>(it) },
    submissionDeadlineEpochMillis = submissionDeadlineEpochMillis,
    votingDeadlineEpochMillis = votingDeadlineEpochMillis,
    serverTimeEpochMillis = serverTimeEpochMillis,
)

internal fun SubmissionDto.toDomain() = Submission(
    id = id,
    challengeId = challengeId,
    authorUserId = authorUserId,
    authorDisplayName = authorDisplayName,
    status = contractEnum<SubmissionStatus>(status),
    thumbnailMediaId = thumbnailMediaId,
    fullMediaId = fullMediaId,
    width = width,
    height = height,
    isMine = isMine,
)

internal fun ChallengeResultDto.toDomain() = ChallengeResult(
    outcome = contractEnum<ChallengeOutcome>(outcome),
    coWinnerSubmissionIds = coWinnerSubmissionIds,
    tallies = tallies.map { Tally(submissionId = it.submissionId, votes = it.votes) },
    ballotCount = ballotCount,
    eligibleVoterCount = eligibleVoterCount,
)

internal fun MyStatusDto.toDomain() = MyStatus(
    isRosterMember = isRosterMember,
    mySubmissionId = mySubmissionId,
    myBallotSubmissionId = myBallotSubmissionId,
    canSubmit = canSubmit,
    canVote = canVote,
)

internal fun ChallengeViewDto.toDomain() = ChallengeView(
    challenge = challenge.toDomain(),
    roster = roster.map { it.toDomain() },
    submissions = submissions.map { it.toDomain() },
    myStatus = myStatus.toDomain(),
    result = result?.toDomain(),
)

internal fun HistoryEntryDto.toDomain() = HistoryEntry(
    challenge = challenge.toDomain(),
    roomName = roomName,
    mySubmissionId = mySubmissionId,
    result = result?.toDomain(),
)

internal inline fun <D, T> PageDto<D>.toDomain(item: (D) -> T): Page<T> =
    Page(items = items.map(item), nextCursor = nextCursor)

internal fun BlockedUserDto.toDomain() = BlockedUser(userId = userId, displayName = displayName)
