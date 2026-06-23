--liquibase formatted sql

--changeset pa:0003-user-avatar
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_name='users' AND column_name='avatar_url'
ALTER TABLE users ADD COLUMN avatar_url TEXT;
