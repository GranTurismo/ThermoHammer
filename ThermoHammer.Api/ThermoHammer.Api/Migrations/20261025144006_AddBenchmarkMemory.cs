using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkMemory : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<double>(
                name: "DbInsert",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "DbQuery",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "DbScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "DbUpdate",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "MemCopy",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "MemLatency",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "MemRead",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "MemScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "MemWrite",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "RamScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<bool>(
                name: "StorageDirect",
                table: "BenchmarkRuns",
                type: "bit",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<double>(
                name: "StorageRandomRead",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "StorageRandomWrite",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "StorageScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "StorageSeqRead",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "StorageSeqWrite",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "DbInsert",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "DbQuery",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "DbScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "DbUpdate",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "MemCopy",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "MemLatency",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "MemRead",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "MemScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "MemWrite",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "RamScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "StorageDirect",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "StorageRandomRead",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "StorageRandomWrite",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "StorageScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "StorageSeqRead",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "StorageSeqWrite",
                table: "BenchmarkRuns");
        }
    }
}
