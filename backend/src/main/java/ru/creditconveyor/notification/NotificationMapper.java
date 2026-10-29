package ru.creditconveyor.notification;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import ru.creditconveyor.api.NotificationDto;

final class NotificationMapper {

    private NotificationMapper() {
    }

    static NotificationDto toDto(Notification notification, ZoneId zone) {
        return new NotificationDto(
                        notification.getId(),
                        NotificationDto.TypeEnum.fromValue(notification.getType()),
                        notification.getTitle(),
                        notification.getBody(),
                        notification.isRead(),
                        OffsetDateTime.ofInstant(notification.getCreatedAt(), zone))
                .applicationId(notification.getApplicationId());
    }
}
