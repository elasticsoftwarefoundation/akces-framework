package org.elasticsoftware.authapi.domain.aggregates;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.authapi.domain.events.errors.InvalidMFACodeErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.InvalidMFADeviceTypeErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.UnsupportedMFADeviceTypeErrorEvent;
import org.elasticsoftware.authapi.domain.commands.SetupMFACommand;
import org.elasticsoftware.authapi.domain.commands.VerifyMFACommand;
import org.elasticsoftware.authapi.domain.events.MFASetupCompletedEvent;
import org.elasticsoftware.authapi.domain.events.MFAVerifiedEvent;
import org.elasticsoftware.authapi.domain.util.TotpUtils;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Multi-factor Authentication Device aggregate that handles MFA-related commands.
 */
@AggregateInfo(
        value = "MFADevice",
        stateClass = MFADeviceState.class,
        indexed = true,
        indexName = "MFADevices")
public class MFADevice implements Aggregate<MFADeviceState> {
    
    @AggregateIdentifier
    private UUID deviceId;
    
    @Override
    public String getName() {
        return "MFADevice";
    }
    
    @Override
    public Class<MFADeviceState> getStateClass() {
        return MFADeviceState.class;
    }
    
    /**
     * Handles MFA device setup.
     */
    @CommandHandler(create = true, produces = {MFASetupCompletedEvent.class}, errors = {InvalidMFADeviceTypeErrorEvent.class, UnsupportedMFADeviceTypeErrorEvent.class})
    public Stream<DomainEvent> handle(SetupMFACommand cmd, MFADeviceState isNull) {
        MFADeviceType deviceType;
        
        try {
            deviceType = MFADeviceType.valueOf(cmd.deviceType());
        } catch (IllegalArgumentException e) {
            return Stream.of(new InvalidMFADeviceTypeErrorEvent(cmd.deviceId(), cmd.deviceType()));
        }
        
        // For now, we only support TOTP
        if (deviceType != MFADeviceType.TOTP) {
            return Stream.of(new UnsupportedMFADeviceTypeErrorEvent(cmd.deviceId(), deviceType));
        }
        
        // Generate secret for TOTP
        String secret = TotpUtils.generateSecret();
        String deviceName = cmd.deviceName() != null && !cmd.deviceName().isEmpty() 
                ? cmd.deviceName() 
                : "Default";
        
        return Stream.of(new MFASetupCompletedEvent(
                cmd.deviceId(),
                cmd.userId(),
                deviceType.name(),
                deviceName,
                secret,
                Instant.now()
        ));
    }
    
    /**
     * Handles MFA verification.
     */
    @CommandHandler(produces = {MFAVerifiedEvent.class}, errors = {UnsupportedMFADeviceTypeErrorEvent.class, InvalidMFACodeErrorEvent.class})
    public Stream<DomainEvent> handle(VerifyMFACommand cmd, MFADeviceState state) {
        // For now, we only support TOTP
        if (state.deviceType() != MFADeviceType.TOTP) {
            return Stream.of(new UnsupportedMFADeviceTypeErrorEvent(cmd.deviceId(), state.deviceType()));
        }
        
        // Verify TOTP code
        boolean isValid = TotpUtils.verifyCode(cmd.code(), state.deviceSecret());
        
        if (!isValid) {
            return Stream.of(new InvalidMFACodeErrorEvent(cmd.deviceId()));
        }
        
        return Stream.of(new MFAVerifiedEvent(
                cmd.deviceId(),
                cmd.userId(),
                Instant.now()
        ));
    }
    
    /**
     * Creates initial MFA device state from MFASetupCompletedEvent.
     */
    @EventSourcingHandler(create = true)
    public MFADeviceState handle(MFASetupCompletedEvent event, MFADeviceState isNull) {
        return new MFADeviceState(
                event.deviceId(),
                event.userId(),
                MFADeviceType.valueOf(event.deviceType()),
                event.deviceSecret(),
                event.deviceName(),
                false,
                event.setupAt(),
                event.setupAt()
        );
    }
    
    /**
     * Updates MFA device state when verification is successful.
     */
    @EventSourcingHandler
    public MFADeviceState handle(MFAVerifiedEvent event, MFADeviceState state) {
        // If the device is not yet verified, mark it as verified
        if (!state.verified()) {
            return state.withVerified(true);
        }
        
        // Otherwise just update the last used timestamp
        return state.withLastUsed();
    }
    
    // Error events moved to their own classes
}