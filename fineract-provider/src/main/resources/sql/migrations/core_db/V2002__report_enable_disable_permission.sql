

INSERT INTO `m_permission` ( `grouping`, `code`, `entity_name`, `action_name`, `can_maker_checker`)
VALUES
	( 'configuration', 'ENABLE_REPORT', 'REPORT', 'ENABLE', 0),
	( 'configuration', 'ENABLE_REPORT_CHECKER', 'REPORT', 'ENABLE_CHECKER', 0);

INSERT INTO `m_permission` ( `grouping`, `code`, `entity_name`, `action_name`, `can_maker_checker`)
VALUES
	( 'configuration', 'DISABLE_REPORT', 'REPORT', 'ENABLE', 0),
	( 'configuration', 'DISABLE_REPORT_CHECKER', 'REPORT', 'DISABLE_CHECKER', 0);
