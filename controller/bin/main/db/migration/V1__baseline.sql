--
-- PostgreSQL database dump
--


-- Dumped from database version 17.11 (Debian 17.11-1.pgdg13+2)
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: forge_task_arguments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.forge_task_arguments (
    task_id character varying(255) NOT NULL,
    argument_value character varying(255)
);


--
-- Name: forge_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.forge_tasks (
    id character varying(255) NOT NULL,
    command character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    exit_code integer,
    status character varying(255) NOT NULL,
    stderr oid,
    stdout oid,
    worker_id character varying(255),
    CONSTRAINT forge_tasks_status_check CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'DISPATCHED'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'LOST'::character varying])::text[])))
);


--
-- Name: forge_tasks forge_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.forge_tasks
    ADD CONSTRAINT forge_tasks_pkey PRIMARY KEY (id);


--
-- Name: forge_task_arguments fk9cgsbm6i6yh6a0vjqdeunp2nf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.forge_task_arguments
    ADD CONSTRAINT fk9cgsbm6i6yh6a0vjqdeunp2nf FOREIGN KEY (task_id) REFERENCES public.forge_tasks(id);


--
-- PostgreSQL database dump complete
--


