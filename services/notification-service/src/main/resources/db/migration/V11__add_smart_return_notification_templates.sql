INSERT INTO notification_templates (type, title_template, body_template) VALUES
    ('SMART_RETURN_PROMPT', 'Are you driving today?', 'Tell Parkio if you want a parking check before you return.'),
    ('SMART_RETURN_AVAILABLE', 'Parking may be available', '{message}')
ON CONFLICT (type) DO NOTHING;
