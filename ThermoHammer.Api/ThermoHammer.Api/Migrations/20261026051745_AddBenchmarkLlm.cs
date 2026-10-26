using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkLlm : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<double>(
                name: "LlmDecodeCpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "LlmDecodeGpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "LlmDecodeScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "LlmFreeGb",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<string>(
                name: "LlmGpu",
                table: "BenchmarkRuns",
                type: "nvarchar(max)",
                nullable: true);

            migrationBuilder.AddColumn<double>(
                name: "LlmPrefillCpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "LlmPrefillGpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "LlmPrefillScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<int>(
                name: "LlmScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "LlmDecodeCpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmDecodeGpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmDecodeScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmFreeGb",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmGpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmPrefillCpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmPrefillGpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmPrefillScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmScore",
                table: "BenchmarkRuns");
        }
    }
}
