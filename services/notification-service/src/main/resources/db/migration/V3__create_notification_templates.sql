-- Notification content templates keyed by notification type. Title/body may contain
-- {placeholders} substituted at send time. Seeded so content is data, not code.
CREATE TABLE notification_templates (
    type           VARCHAR(32)  NOT NULL,
    title_template VARCHAR(200) NOT NULL,
    body_template  VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_notification_templates PRIMARY KEY (type)
);

INSERT INTO notification_templates (type, title_template, body_template) VALUES
    ('LEVEL_UP',     'Level up!',        'Congratulations — you reached level {level}.'),
    ('POINT_EARNED', 'You earned points', 'You earned {points} points. Total: {totalPoints}.'),
    ('WARNING',      'Heads up',          '{message}');
