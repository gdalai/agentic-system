-- Artifacts each task produced; staged ones are discarded if the run fails.
ALTER TABLE task_execution ADD COLUMN artifacts VARCHAR(2000);
