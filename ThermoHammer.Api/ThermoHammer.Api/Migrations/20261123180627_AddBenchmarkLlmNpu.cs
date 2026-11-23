using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkLlmNpu : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<int>(
                name: "LlmCpuScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "LlmDecodeNpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "LlmGpuScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<string>(
                name: "LlmNpu",
                table: "BenchmarkRuns",
                type: "nvarchar(max)",
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "LlmNpuScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "LlmPrefillNpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "LlmCpuScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmDecodeNpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmGpuScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmNpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmNpuScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "LlmPrefillNpu",
                table: "BenchmarkRuns");
        }
    }
}
