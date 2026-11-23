BEGIN TRANSACTION;
IF NOT EXISTS (
    SELECT * FROM [__EFMigrationsHistory]
    WHERE [MigrationId] = N'20261123180627_AddBenchmarkLlmNpu'
)
BEGIN
    ALTER TABLE [BenchmarkRuns] ADD [LlmCpuScore] int NOT NULL DEFAULT 0;
END;

IF NOT EXISTS (
    SELECT * FROM [__EFMigrationsHistory]
    WHERE [MigrationId] = N'20261123180627_AddBenchmarkLlmNpu'
)
BEGIN
    ALTER TABLE [BenchmarkRuns] ADD [LlmDecodeNpu] float NOT NULL DEFAULT 0.0E0;
END;

IF NOT EXISTS (
    SELECT * FROM [__EFMigrationsHistory]
    WHERE [MigrationId] = N'20261123180627_AddBenchmarkLlmNpu'
)
BEGIN
    ALTER TABLE [BenchmarkRuns] ADD [LlmGpuScore] int NOT NULL DEFAULT 0;
END;

IF NOT EXISTS (
    SELECT * FROM [__EFMigrationsHistory]
    WHERE [MigrationId] = N'20261123180627_AddBenchmarkLlmNpu'
)
BEGIN
    ALTER TABLE [BenchmarkRuns] ADD [LlmNpu] nvarchar(max) NULL;
END;

IF NOT EXISTS (
    SELECT * FROM [__EFMigrationsHistory]
    WHERE [MigrationId] = N'20261123180627_AddBenchmarkLlmNpu'
)
BEGIN
    ALTER TABLE [BenchmarkRuns] ADD [LlmNpuScore] int NOT NULL DEFAULT 0;
END;

IF NOT EXISTS (
    SELECT * FROM [__EFMigrationsHistory]
    WHERE [MigrationId] = N'20261123180627_AddBenchmarkLlmNpu'
)
BEGIN
    ALTER TABLE [BenchmarkRuns] ADD [LlmPrefillNpu] float NOT NULL DEFAULT 0.0E0;
END;

IF NOT EXISTS (
    SELECT * FROM [__EFMigrationsHistory]
    WHERE [MigrationId] = N'20261123180627_AddBenchmarkLlmNpu'
)
BEGIN
    INSERT INTO [__EFMigrationsHistory] ([MigrationId], [ProductVersion])
    VALUES (N'20261123180627_AddBenchmarkLlmNpu', N'10.0.10');
END;

COMMIT;
GO

